package br.com.mss.domino.net.grpc;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.DtoMapper;
import br.com.mss.domino.net.GameEvent;
import br.com.mss.domino.net.GameEventListener;
import br.com.mss.domino.net.GameTransport;
import br.com.mss.domino.net.PasswordHashing;
import br.com.mss.domino.net.SessionExpiredException;
import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.dto.MatchInfoDto;
import br.com.mss.domino.net.dto.MoveDto;
import br.com.mss.domino.net.dto.PlayerStatsDto;
import br.com.mss.domino.net.dto.RankingEntryDto;
import br.com.mss.domino.net.grpc.proto.CreateMatchRequest;
import br.com.mss.domino.net.grpc.proto.DominoHostGrpc;
import br.com.mss.domino.net.grpc.proto.GetMyStatsRequest;
import br.com.mss.domino.net.grpc.proto.GetRankingRequest;
import br.com.mss.domino.net.grpc.proto.JoinMessage;
import br.com.mss.domino.net.grpc.proto.JoinRequest;
import br.com.mss.domino.net.grpc.proto.ListMatchesRequest;
import br.com.mss.domino.net.grpc.proto.ReconnectRequest;
import br.com.mss.domino.net.grpc.proto.SendChatRequest;
import br.com.mss.domino.net.grpc.proto.SetReadyRequest;
import br.com.mss.domino.net.grpc.proto.SubmitMoveRequest;
import br.com.mss.domino.net.grpc.proto.TakeChairRequest;
import br.com.mss.domino.net.grpc.proto.TargetSeatRequest;
import br.com.mss.domino.net.grpc.proto.TokenRequest;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lado do cliente sobre gRPC (ADR-0001) — o transporte de verdade desde a Fase 6a-4 (substituiu o
 * antigo {@code RmiClientTransport}, apagado). Implementa {@link GameTransport}, então a camada
 * {@code app}/{@code ui} não vê {@code net.grpc}. Sem callback exportado (não existe no gRPC): o
 * stream de resposta de {@code Join}/{@code Reconnect} <b>é</b> o canal de eventos — a 1ª mensagem
 * é sempre a sessão (bloqueia {@link #join}/{@link #reconnect} até ela chegar ou o RPC falhar), as
 * seguintes viram {@link GameEvent} despachados aos {@link GameEventListener}.
 *
 * <p>Os eventos chegam numa thread do gRPC; quem os joga na EDT é o {@code MatchController} da
 * camada {@code app} (ADR-0010/ADR-0016) — igual ao RMI.
 */
public final class GrpcClientTransport implements GameTransport {

  private static final Logger LOG = LoggerFactory.getLogger(GrpcClientTransport.class);

  /** ADR-0019: mesmo espírito do RMI — retry curto e silencioso antes de propagar a falha. */
  private static final int RECONNECT_ATTEMPTS = 2;

  private static final long RECONNECT_BACKOFF_MILLIS = 200;
  private static final long JOIN_TIMEOUT_SECONDS = 10;

  private final String hostAddress;
  private final int port;
  private final String guestId;
  private final boolean tls;
  private final String devCaFile;
  private final List<GameEventListener> listeners = new CopyOnWriteArrayList<>();

  private ManagedChannel channel;
  private DominoHostGrpc.DominoHostBlockingStub blocking;
  private DominoHostGrpc.DominoHostStub async;
  private String token;

  /** Sem identidade persistente (M5-4, ADR-0029) — equivalente a {@code guestId} vazio. */
  public GrpcClientTransport(String hostAddress, int port) {
    this(hostAddress, port, null);
  }

  /**
   * Com {@code guestId} (M5-4, ADR-0029): o {@code PlayerId} do perfil ativo (ADR-0018),
   * auto-declarado em todo {@code Join} desta sessão — {@code null}/vazio equivale ao construtor
   * sem esse parâmetro.
   */
  public GrpcClientTransport(String hostAddress, int port, String guestId) {
    this(hostAddress, port, guestId, false);
  }

  /**
   * Com {@code tls} (M6-02, ADR-0030): {@code true} abre o canal com {@code TlsChannelCredentials}
   * e validação de certificado obrigatória; {@code false} é texto puro, como os construtores acima.
   * A CA de desenvolvimento, se houver, vem da propriedade {@code domino.tls.devCaFile}.
   */
  public GrpcClientTransport(String hostAddress, int port, String guestId, boolean tls) {
    this(hostAddress, port, guestId, tls, ClientChannelSecurity.devCaFileFromSystem());
  }

  /** Para teste: CA de desenvolvimento explícita, sem depender de propriedade de sistema. */
  GrpcClientTransport(String hostAddress, int port, String guestId, boolean tls, String devCaFile) {
    this.hostAddress = hostAddress;
    this.port = port;
    this.guestId = guestId == null ? "" : guestId;
    this.tls = tls;
    this.devCaFile = devCaFile;
  }

  @Override
  public void open() throws TransportException {
    try {
      ChannelCredentials credentials = ClientChannelSecurity.credentials(tls, devCaFile);
      channel = Grpc.newChannelBuilderForAddress(hostAddress, port, credentials).build();
      blocking = DominoHostGrpc.newBlockingStub(channel);
      async = DominoHostGrpc.newStub(channel);
      LOG.info("OPEN host={}:{} tls={}", hostAddress, port, tls);
    } catch (IOException e) {
      throw new TransportException("CA de desenvolvimento ilegível: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new TransportException("não foi possível ligar a " + hostAddress, e);
    }
  }

  @Override
  public List<MatchInfoDto> listMatches() throws TransportException {
    requireOpen();
    try {
      return blocking.listMatches(ListMatchesRequest.getDefaultInstance()).getMatchesList().stream()
          .map(ProtoMapper::matchInfo)
          .toList();
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao listar partidas", e);
    }
  }

  @Override
  public String createMatch(
      String name, GameMode mode, int size, int turnSeconds, int timeBankSeconds)
      throws TransportException {
    return createMatch(name, mode, size, turnSeconds, timeBankSeconds, "");
  }

  /** Fase 4-1 (ADR-0014): {@code password} nunca viaja — só o hash SHA-256 dela. */
  @Override
  public String createMatch(
      String name, GameMode mode, int size, int turnSeconds, int timeBankSeconds, String password)
      throws TransportException {
    requireOpen();
    try {
      String hash = PasswordHashing.hash(password);
      return blocking
          .createMatch(
              CreateMatchRequest.newBuilder()
                  .setName(name)
                  .setMode(ProtoMapper.gameMode(DtoMapper.mode(mode)))
                  .setSize(size)
                  .setClock(
                      br.com.mss.domino.net.grpc.proto.ClockSettings.newBuilder()
                          .setTurnSeconds(turnSeconds)
                          .setTimeBankSeconds(timeBankSeconds)
                          .build())
                  .setPasswordHash(hash == null ? "" : hash)
                  .build())
          .getMatchId();
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao criar a partida", e);
    }
  }

  @Override
  public Session join(String matchId, String name) throws TransportException {
    return join(matchId, name, "");
  }

  /** Fase 4-1 (ADR-0014): {@code password} nunca viaja — só o hash SHA-256 dela. */
  @Override
  public Session join(String matchId, String name, String password) throws TransportException {
    requireOpen();
    String hash = PasswordHashing.hash(password);
    EventStreamObserver observer = new EventStreamObserver();
    async.join(
        JoinRequest.newBuilder()
            .setMatchId(matchId)
            .setName(name)
            .setPasswordHash(hash == null ? "" : hash)
            .setGuestId(guestId)
            .build(),
        observer);
    Session session = awaitSession(observer, "entrar na partida");
    token = session.token();
    LOG.info("JOIN match={} seat={}", matchId, session.seat());
    return session;
  }

  @Override
  public Session reconnect(String reconnectToken) throws TransportException {
    requireOpen();
    EventStreamObserver observer = new EventStreamObserver();
    async.reconnect(ReconnectRequest.newBuilder().setToken(reconnectToken).build(), observer);
    Session session;
    try {
      session = awaitSession(observer, "reconectar");
    } catch (TransportException e) {
      // BUG-009: NOT_FOUND num reconnect = token desconhecido ou partida que já não existe; o
      // token não serve mais. Queda de rede (UNAVAILABLE etc.) continua TransportException comum.
      if (e.getCause() instanceof StatusRuntimeException sre
          && sre.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new SessionExpiredException(e.getMessage(), sre);
      }
      throw e;
    }
    token = session.token();
    LOG.info("RECONNECT seat={}", session.seat());
    return session;
  }

  private Session awaitSession(EventStreamObserver observer, String action)
      throws TransportException {
    try {
      return observer.sessionFuture.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      LOG.warn("{} falhou", action, cause);
      throw new TransportException("falha ao " + action + ": " + statusMessage(cause), cause);
    } catch (TimeoutException e) {
      throw new TransportException("tempo esgotado ao " + action, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransportException("interrompido ao " + action, e);
    }
  }

  private static String statusMessage(Throwable cause) {
    if (cause instanceof StatusRuntimeException sre) {
      String description = sre.getStatus().getDescription();
      return description != null ? description : sre.getStatus().getCode().toString();
    }
    return cause.getMessage();
  }

  @Override
  public void takeChair(int chair) throws TransportException {
    requireJoined();
    try {
      blocking.takeChair(TakeChairRequest.newBuilder().setToken(token).setChair(chair).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("não foi possível sentar: " + statusMessage(e), e);
    }
  }

  @Override
  public void leaveChair() throws TransportException {
    requireJoined();
    try {
      blocking.leaveChair(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao levantar da cadeira", e);
    }
  }

  @Override
  public void setReady(boolean ready) throws TransportException {
    requireJoined();
    try {
      blocking.setReady(SetReadyRequest.newBuilder().setToken(token).setReady(ready).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao mudar o 'pronto': " + statusMessage(e), e);
    }
  }

  @Override
  public void start() throws TransportException {
    requireJoined();
    try {
      blocking.start(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao iniciar a partida", e);
    }
  }

  @Override
  public void sendMove(MoveDto move) throws TransportException {
    requireJoined();
    SubmitMoveRequest request =
        SubmitMoveRequest.newBuilder().setToken(token).setMove(ProtoMapper.move(move)).build();
    try {
      LOG.debug("SEND move seat={} tile={} end={}", move.seat(), move.tile(), move.end());
      blocking.submitMove(request);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
        // recusa por regra (ADR-0002) — não é queda de rede, não adianta reconectar e reenviar.
        LOG.warn("move recusado: {}", statusMessage(e));
        throw new TransportException("jogada recusada: " + statusMessage(e), e);
      }
      retryOrGiveUp(e, "a jogada", () -> blocking.submitMove(request));
    }
  }

  @Override
  public void sendChat(String text) throws TransportException {
    requireJoined();
    SendChatRequest request = SendChatRequest.newBuilder().setToken(token).setText(text).build();
    try {
      blocking.sendChat(request);
    } catch (StatusRuntimeException e) {
      retryOrGiveUp(e, "o chat", () -> blocking.sendChat(request));
    }
  }

  /**
   * ADR-0019: queda de rede transitória num RPC unário — tenta reconectar sozinho (janela de 5 min,
   * ADR-0013) e reenviar, até {@link #RECONNECT_ATTEMPTS} vezes, antes de propagar ao jogador.
   * Recusa por regra (não é falha de rede) sobe direto — ver {@link #sendMove}.
   */
  private void retryOrGiveUp(StatusRuntimeException original, String what, Runnable resend)
      throws TransportException {
    LOG.warn("falha de rede ao enviar {} — tentando reconectar sozinho (ADR-0019)", what, original);
    for (int attempt = 1; attempt <= RECONNECT_ATTEMPTS; attempt++) {
      sleepBackoff();
      if (!tryReconnect()) {
        continue;
      }
      try {
        resend.run();
        LOG.info("reconectou sozinho e reenviou {} (tentativa {})", what, attempt);
        return;
      } catch (StatusRuntimeException stillFailing) {
        if (stillFailing.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
          // reconectou, mas agora a ação em si é recusada por regra — não é mais falha de rede.
          throw new TransportException("recusado: " + statusMessage(stillFailing), stillFailing);
        }
        LOG.warn("{} ainda falhou após reconectar (tentativa {})", what, attempt, stillFailing);
      }
    }
    throw new TransportException(
        "falha de rede ao enviar " + what + " — não reconectou sozinho", original);
  }

  /** Reabre o stream de eventos com o mesmo token (ADR-0019) — {@code false} se ainda sem rede. */
  private boolean tryReconnect() {
    try {
      reconnect(token);
      return true;
    } catch (TransportException e) {
      LOG.debug("tentativa de reconectar sozinho ainda falhou: {}", e.getMessage());
      return false;
    }
  }

  private static void sleepBackoff() {
    try {
      Thread.sleep(RECONNECT_BACKOFF_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void requestElimination(int targetSeat) throws TransportException {
    requireJoined();
    try {
      blocking.requestElimination(
          TargetSeatRequest.newBuilder().setToken(token).setTargetSeat(targetSeat).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao pedir eliminação: " + statusMessage(e), e);
    }
  }

  @Override
  public void confirmElimination() throws TransportException {
    requireJoined();
    try {
      blocking.confirmElimination(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao confirmar eliminação: " + statusMessage(e), e);
    }
  }

  @Override
  public void declineElimination() throws TransportException {
    requireJoined();
    try {
      blocking.declineElimination(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao recusar eliminação: " + statusMessage(e), e);
    }
  }

  @Override
  public void requestAbort() throws TransportException {
    requireJoined();
    try {
      blocking.requestAbort(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao pedir aborto: " + statusMessage(e), e);
    }
  }

  @Override
  public void confirmAbort() throws TransportException {
    requireJoined();
    try {
      blocking.confirmAbort(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao confirmar aborto: " + statusMessage(e), e);
    }
  }

  @Override
  public void declineAbort() throws TransportException {
    requireJoined();
    try {
      blocking.declineAbort(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao recusar aborto: " + statusMessage(e), e);
    }
  }

  // --- revanche e retorno à lista (Fase 4.5-4, ADR-0024) ---

  /**
   * Sai da partida terminada sem fechar o canal gRPC — o mesmo host continua servindo {@code
   * listMatches}/{@code createMatch}/{@code join} pra próxima partida. {@code leave} já trata "sair
   * de uma partida terminada" como limpeza total do lado do host; não é o mesmo caminho de {@link
   * #close()}, que também derruba o canal.
   */
  @Override
  public void leaveMatch() {
    if (blocking != null && token != null) {
      try {
        blocking.leave(TokenRequest.newBuilder().setToken(token).build());
      } catch (StatusRuntimeException e) {
        LOG.warn("falha ao sair da partida (seguindo mesmo assim)", e);
      }
    }
    token = null;
  }

  @Override
  public void requestRematch() throws TransportException {
    requireJoined();
    try {
      blocking.requestRematch(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao pedir revanche: " + statusMessage(e), e);
    }
  }

  @Override
  public void confirmRematch() throws TransportException {
    requireJoined();
    try {
      blocking.confirmRematch(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao confirmar revanche: " + statusMessage(e), e);
    }
  }

  @Override
  public void declineRematch() throws TransportException {
    requireJoined();
    try {
      blocking.declineRematch(TokenRequest.newBuilder().setToken(token).build());
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao recusar revanche: " + statusMessage(e), e);
    }
  }

  @Override
  public PlayerStatsDto getMyStats(String guestId, GameMode mode) throws TransportException {
    requireOpen();
    try {
      return ProtoMapper.playerStats(
          blocking.getMyStats(
              GetMyStatsRequest.newBuilder()
                  .setGuestId(guestId == null ? "" : guestId)
                  .setMode(ProtoMapper.gameMode(DtoMapper.mode(mode)))
                  .build()));
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao consultar estatísticas: " + statusMessage(e), e);
    }
  }

  @Override
  public List<RankingEntryDto> getRanking(GameMode mode, int limit) throws TransportException {
    requireOpen();
    try {
      return blocking
          .getRanking(
              GetRankingRequest.newBuilder()
                  .setMode(ProtoMapper.gameMode(DtoMapper.mode(mode)))
                  .setLimit(limit)
                  .build())
          .getEntriesList()
          .stream()
          .map(ProtoMapper::rankingEntry)
          .toList();
    } catch (StatusRuntimeException e) {
      throw new TransportException("falha ao consultar ranking: " + statusMessage(e), e);
    }
  }

  @Override
  public void addListener(GameEventListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(GameEventListener listener) {
    listeners.remove(listener);
  }

  @Override
  public void close() {
    try {
      if (blocking != null && token != null) {
        blocking.leave(TokenRequest.newBuilder().setToken(token).build());
      }
    } catch (StatusRuntimeException ignored) {
      // encerrando de qualquer jeito
    }
    if (channel != null) {
      channel.shutdownNow();
      try {
        channel.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      channel = null;
    }
    listeners.clear();
    blocking = null;
    async = null;
    token = null;
  }

  private void dispatch(GameEvent event) {
    for (GameEventListener listener : listeners) {
      listener.onEvent(event);
    }
  }

  private void requireOpen() throws TransportException {
    if (blocking == null) {
      throw new TransportException("transporte não ligado (chame open())");
    }
  }

  private void requireJoined() throws TransportException {
    if (blocking == null || token == null) {
      throw new TransportException("sem sessão numa partida");
    }
  }

  /**
   * A 1ª mensagem do stream de {@code Join}/{@code Reconnect} é sempre a sessão (completa {@link
   * #sessionFuture}); as seguintes são {@link GameEvent}, despachadas direto. Uma falha antes da
   * sessão chegar é recusa de entrada (senha errada, partida cheia — vem como {@code
   * FAILED_PRECONDITION}) ou {@code NOT_FOUND}; falha depois é queda de conexão em pleno jogo — sem
   * retry automático aqui (quem chama {@link #sendMove}/{@link #sendChat} já tenta reconectar
   * sozinho; uma reconexão "de fora", pela UI, é a mesma {@link #reconnect}).
   */
  private final class EventStreamObserver implements StreamObserver<JoinMessage> {
    private final CompletableFuture<Session> sessionFuture = new CompletableFuture<>();

    @Override
    public void onNext(JoinMessage message) {
      if (message.hasSession()) {
        var s = message.getSession();
        sessionFuture.complete(
            new Session(s.getToken(), s.getSeat(), ProtoMapper.snapshot(s.getSnapshot())));
      } else if (message.hasEvent()) {
        dispatch(ProtoMapper.event(message.getEvent()));
      }
    }

    @Override
    public void onError(Throwable t) {
      if (!sessionFuture.isDone()) {
        sessionFuture.completeExceptionally(t);
      } else if (t instanceof StatusRuntimeException sre
          && sre.getStatus().getCode() == Status.Code.ABORTED) {
        // BUG-013: outra janela retomou o mesmo assento; esta deixa de receber eventos.
        LOG.warn("stream de eventos encerrado pelo host: {}", statusMessage(sre));
      } else {
        LOG.warn("stream de eventos caiu", t);
      }
    }

    @Override
    public void onCompleted() {
      // servidor fechou o stream de propósito (ex.: leave) — nada a fazer.
    }
  }
}
