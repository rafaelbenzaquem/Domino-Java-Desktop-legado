package br.com.mss.domino.net.grpc;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.AccountCredentials;
import br.com.mss.domino.net.AccountRefusedException;
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
import io.grpc.ClientInterceptor;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
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
 *
 * <p><b>Conta MSS (M1 deste repositório, Domino:M7):</b> com {@link AccountCredentials} da
 * identidade, toda chamada leva {@code authorization: Bearer <acesso de jogo>} ({@link
 * GameCallCredentials}); um {@code UNAUTHENTICATED} do servidor gera no máximo uma nova tentativa
 * com o acesso renovado ({@link CredentialRetry}); recusas de conta viram {@link
 * AccountRefusedException} com a mensagem do caso ({@link GrpcErrors}). Durante a partida, {@code
 * GetMyStats} periódico ({@link #ACCESS_KEEPALIVE}) entrega ao servidor um acesso renovado, e um
 * stream de eventos encerrado com {@code UNAUTHENTICATED} é reaberto uma vez com o acesso renovado
 * (lições TchowStrick-Java-Desktop-Legado:M1/BUG-005 e TchowStrick:BUG-020). Sem conta ({@link
 * AccountCredentials#none()}), nada disso acontece — comportamento anterior.
 */
public final class GrpcClientTransport implements GameTransport {

  private static final Logger LOG = LoggerFactory.getLogger(GrpcClientTransport.class);

  /** ADR-0019: mesmo espírito do RMI — retry curto e silencioso antes de propagar a falha. */
  private static final int RECONNECT_ATTEMPTS = 2;

  private static final long RECONNECT_BACKOFF_MILLIS = 200;
  private static final long JOIN_TIMEOUT_SECONDS = 10;

  /**
   * Intervalo do {@code GetMyStats} que renova o acesso de jogo durante a partida (só com conta
   * MSS). A biblioteca reaproveita o acesso em cache até ~1 min antes de vencer (10 min); com 1 min
   * o servidor sempre tem um acesso recente da conta (lição do TchowStrick-Java-Desktop-Legado:M1).
   */
  static final Duration ACCESS_KEEPALIVE = Duration.ofMinutes(1);

  private final String hostAddress;
  private final int port;
  private final String guestId;
  private final boolean tls;
  private final String devCaFile;
  private final AccountCredentials accountCredentials;
  private final List<GameEventListener> listeners = new CopyOnWriteArrayList<>();

  private ManagedChannel channel;
  private volatile DominoHostGrpc.DominoHostBlockingStub blocking;
  private DominoHostGrpc.DominoHostStub async;
  private volatile String token;
  private volatile boolean closing;
  private ScheduledExecutorService keepalive;

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
    this(hostAddress, port, guestId, tls, AccountCredentials.none());
  }

  /**
   * Com {@code accountCredentials} (M1): consultada a cada chamada; com a identidade MSS, o acesso
   * de jogo vai em {@code authorization: Bearer} e é renovado antes de vencer. {@code guestId}, num
   * servidor com identidade, é o {@code account_id} da conta (o servidor deriva da conta de
   * qualquer forma). Credencial de conta só sobre TLS, salvo para localhost.
   */
  public GrpcClientTransport(
      String hostAddress,
      int port,
      String guestId,
      boolean tls,
      AccountCredentials accountCredentials) {
    this(
        hostAddress,
        port,
        guestId,
        tls,
        ClientChannelSecurity.devCaFileFromSystem(),
        accountCredentials);
  }

  /** Para teste: CA de desenvolvimento explícita, sem depender de propriedade de sistema. */
  GrpcClientTransport(String hostAddress, int port, String guestId, boolean tls, String devCaFile) {
    this(hostAddress, port, guestId, tls, devCaFile, AccountCredentials.none());
  }

  GrpcClientTransport(
      String hostAddress,
      int port,
      String guestId,
      boolean tls,
      String devCaFile,
      AccountCredentials accountCredentials) {
    this.hostAddress = hostAddress;
    this.port = port;
    this.guestId = guestId == null ? "" : guestId;
    this.tls = tls;
    this.devCaFile = devCaFile;
    this.accountCredentials =
        accountCredentials == null ? AccountCredentials.none() : accountCredentials;
  }

  @Override
  public void open() throws TransportException {
    String violation = CredentialRetry.plaintextViolation(accountCredentials, hostAddress, tls);
    if (violation != null) {
      throw new TransportException(violation);
    }
    try {
      ChannelCredentials credentials = ClientChannelSecurity.credentials(tls, devCaFile);
      channel = Grpc.newChannelBuilderForAddress(hostAddress, port, credentials).build();
      ClientInterceptor account = new GameCallCredentials(accountCredentials);
      blocking = DominoHostGrpc.newBlockingStub(channel).withInterceptors(account);
      async = DominoHostGrpc.newStub(channel).withInterceptors(account);
      closing = false;
      LOG.info(
          "OPEN host={}:{} tls={} conta={}", hostAddress, port, tls, accountCredentials.source());
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
      return rpc(() -> blocking.listMatches(ListMatchesRequest.getDefaultInstance()))
          .getMatchesList()
          .stream()
          .map(ProtoMapper::matchInfo)
          .toList();
    } catch (StatusRuntimeException e) {
      throw failure("falha ao listar partidas", e);
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
      CreateMatchRequest request =
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
              .build();
      return rpc(() -> blocking.createMatch(request)).getMatchId();
    } catch (StatusRuntimeException e) {
      throw failure("falha ao criar a partida", e);
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
    JoinRequest request =
        JoinRequest.newBuilder()
            .setMatchId(matchId)
            .setName(name)
            .setPasswordHash(hash == null ? "" : hash)
            .setGuestId(guestId)
            .build();
    Session session =
        withAccessRetry(
            () -> {
              EventStreamObserver observer = new EventStreamObserver();
              async.join(request, observer);
              return awaitSession(observer, "entrar na partida");
            });
    token = session.token();
    LOG.info("JOIN match={} seat={}", matchId, session.seat());
    startAccessKeepalive();
    return session;
  }

  @Override
  public Session reconnect(String reconnectToken) throws TransportException {
    requireOpen();
    ReconnectRequest request = ReconnectRequest.newBuilder().setToken(reconnectToken).build();
    Session session;
    try {
      session =
          withAccessRetry(
              () -> {
                EventStreamObserver observer = new EventStreamObserver();
                async.reconnect(request, observer);
                return awaitSession(observer, "reconectar");
              });
    } catch (AccountRefusedException e) {
      throw e;
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
    startAccessKeepalive();
    return session;
  }

  /**
   * Abre o stream ({@code Join}/{@code Reconnect}) e, se o servidor recusar a credencial com {@code
   * UNAUTHENTICATED} e ela for renovável, tenta de novo <b>uma</b> vez com o acesso renovado.
   */
  private Session withAccessRetry(StreamAttempt attempt) throws TransportException {
    try {
      return attempt.open();
    } catch (TransportException e) {
      if (!CredentialRetry.shouldRetry(accountCredentials, e.getCause())) {
        throw e;
      }
      LOG.info(
          "acesso de jogo recusado ao abrir o stream em {}:{}; renovando uma vez",
          hostAddress,
          port);
      return attempt.open();
    }
  }

  @FunctionalInterface
  private interface StreamAttempt {
    Session open() throws TransportException;
  }

  /** Executa um RPC unário com no máximo uma nova tentativa por credencial recusada. */
  private <T> T rpc(Supplier<T> call) {
    return CredentialRetry.call(accountCredentials, call);
  }

  /**
   * Recusa de conta conhecida ({@link AccountRefusedException}, mensagem do caso) ou a falha de
   * sempre, com {@code fallback} — servidores sem conta não mudam de comportamento.
   */
  private TransportException failure(String fallback, Throwable cause) {
    return GrpcErrors.accountRefusal(cause, accountCredentials.source())
        .<TransportException>map(refusal -> refusal)
        .orElseGet(() -> new TransportException(fallback, cause));
  }

  /**
   * Só com conta MSS: renova o acesso no servidor durante a partida ({@link #ACCESS_KEEPALIVE}).
   */
  private synchronized void startAccessKeepalive() {
    if (keepalive != null
        || accountCredentials.source() != AccountCredentials.Source.MSS_IDENTITY) {
      return;
    }
    ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "domino-acesso-identidade");
              t.setDaemon(true);
              return t;
            });
    long period = ACCESS_KEEPALIVE.toSeconds();
    executor.scheduleWithFixedDelay(this::refreshStreamAccess, period, period, TimeUnit.SECONDS);
    keepalive = executor;
  }

  /** {@code true} enquanto o {@code GetMyStats} periódico da conta MSS estiver agendado. */
  synchronized boolean accessKeepaliveRunning() {
    return keepalive != null;
  }

  /** Um {@code GetMyStats} com o acesso atual (renovado pela biblioteca, se preciso). */
  void refreshStreamAccess() {
    DominoHostGrpc.DominoHostBlockingStub stub = blocking;
    if (stub == null) {
      return;
    }
    try {
      GetMyStatsRequest request =
          GetMyStatsRequest.newBuilder()
              .setGuestId(guestId)
              .setMode(ProtoMapper.gameMode(DtoMapper.mode(GameMode.FREE_FOR_ALL)))
              .build();
      rpc(() -> stub.withDeadlineAfter(5, TimeUnit.SECONDS).getMyStats(request));
    } catch (RuntimeException e) {
      // Sem derrubar a partida: se o acesso não puder ser renovado, o stream decide.
      LOG.warn(
          "não consegui renovar o acesso da partida em {}:{}: {}",
          hostAddress,
          port,
          failure("falha ao renovar o acesso", e).getMessage());
    }
  }

  private synchronized void stopAccessKeepalive() {
    if (keepalive != null) {
      keepalive.shutdownNow();
      keepalive = null;
    }
  }

  /**
   * O stream de eventos caiu com {@code UNAUTHENTICATED} (acesso vencido ou revogado durante a
   * partida): descarta o acesso em cache e reabre o stream <b>uma</b> vez com o mesmo token de
   * assento e o acesso renovado, numa thread própria (nunca na thread do gRPC).
   */
  private void recoverStreamAccess() {
    String current = token;
    if (closing || current == null || !accountCredentials.renewAfterRejection()) {
      return;
    }
    Thread recovery =
        new Thread(
            () -> {
              try {
                reconnect(current);
                LOG.info("stream de eventos reaberto com o acesso de jogo renovado");
              } catch (TransportException e) {
                LOG.warn(
                    "não reabriu o stream de eventos com o acesso renovado: {}", e.getMessage());
              }
            },
            "domino-reconecta-stream");
    recovery.setDaemon(true);
    recovery.start();
  }

  private Session awaitSession(EventStreamObserver observer, String action)
      throws TransportException {
    try {
      return observer.sessionFuture.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      LOG.warn("{} falhou: {}", action, statusMessage(cause));
      throw failure("falha ao " + action + ": " + statusMessage(cause), cause);
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
      TakeChairRequest request =
          TakeChairRequest.newBuilder().setToken(token).setChair(chair).build();
      rpc(() -> blocking.takeChair(request));
    } catch (StatusRuntimeException e) {
      throw failure("não foi possível sentar: " + statusMessage(e), e);
    }
  }

  @Override
  public void leaveChair() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.leaveChair(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao levantar da cadeira", e);
    }
  }

  @Override
  public void setReady(boolean ready) throws TransportException {
    requireJoined();
    try {
      SetReadyRequest request =
          SetReadyRequest.newBuilder().setToken(token).setReady(ready).build();
      rpc(() -> blocking.setReady(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao mudar o 'pronto': " + statusMessage(e), e);
    }
  }

  @Override
  public void start() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.start(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao iniciar a partida", e);
    }
  }

  @Override
  public void sendMove(MoveDto move) throws TransportException {
    requireJoined();
    SubmitMoveRequest request =
        SubmitMoveRequest.newBuilder().setToken(token).setMove(ProtoMapper.move(move)).build();
    try {
      LOG.debug("SEND move seat={} tile={} end={}", move.seat(), move.tile(), move.end());
      rpc(() -> blocking.submitMove(request));
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
      rpc(() -> blocking.sendChat(request));
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
    var refusal = GrpcErrors.accountRefusal(original, accountCredentials.source());
    if (refusal.isPresent()) {
      // recusa de conta (M1) não é queda de rede: reconectar e reenviar não resolve.
      throw refusal.get();
    }
    LOG.warn("falha de rede ao enviar {} — tentando reconectar sozinho (ADR-0019)", what, original);
    for (int attempt = 1; attempt <= RECONNECT_ATTEMPTS; attempt++) {
      sleepBackoff();
      if (!tryReconnect()) {
        continue;
      }
      try {
        rpc(
            () -> {
              resend.run();
              return null;
            });
        LOG.info("reconectou sozinho e reenviou {} (tentativa {})", what, attempt);
        return;
      } catch (StatusRuntimeException stillFailing) {
        if (stillFailing.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
          // reconectou, mas agora a ação em si é recusada por regra — não é mais falha de rede.
          throw new TransportException("recusado: " + statusMessage(stillFailing), stillFailing);
        }
        var stillRefused = GrpcErrors.accountRefusal(stillFailing, accountCredentials.source());
        if (stillRefused.isPresent()) {
          throw stillRefused.get();
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
      TargetSeatRequest request =
          TargetSeatRequest.newBuilder().setToken(token).setTargetSeat(targetSeat).build();
      rpc(() -> blocking.requestElimination(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao pedir eliminação: " + statusMessage(e), e);
    }
  }

  @Override
  public void confirmElimination() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.confirmElimination(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao confirmar eliminação: " + statusMessage(e), e);
    }
  }

  @Override
  public void declineElimination() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.declineElimination(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao recusar eliminação: " + statusMessage(e), e);
    }
  }

  @Override
  public void requestAbort() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.requestAbort(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao pedir aborto: " + statusMessage(e), e);
    }
  }

  @Override
  public void confirmAbort() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.confirmAbort(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao confirmar aborto: " + statusMessage(e), e);
    }
  }

  @Override
  public void declineAbort() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.declineAbort(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao recusar aborto: " + statusMessage(e), e);
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
    stopAccessKeepalive();
    if (blocking != null && token != null) {
      try {
        TokenRequest request = tokenRequest();
        rpc(() -> blocking.leave(request));
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
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.requestRematch(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao pedir revanche: " + statusMessage(e), e);
    }
  }

  @Override
  public void confirmRematch() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.confirmRematch(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao confirmar revanche: " + statusMessage(e), e);
    }
  }

  @Override
  public void declineRematch() throws TransportException {
    requireJoined();
    try {
      TokenRequest request = tokenRequest();
      rpc(() -> blocking.declineRematch(request));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao recusar revanche: " + statusMessage(e), e);
    }
  }

  @Override
  public PlayerStatsDto getMyStats(String guestId, GameMode mode) throws TransportException {
    requireOpen();
    try {
      GetMyStatsRequest request =
          GetMyStatsRequest.newBuilder()
              .setGuestId(guestId == null ? "" : guestId)
              .setMode(ProtoMapper.gameMode(DtoMapper.mode(mode)))
              .build();
      return ProtoMapper.playerStats(rpc(() -> blocking.getMyStats(request)));
    } catch (StatusRuntimeException e) {
      throw failure("falha ao consultar estatísticas: " + statusMessage(e), e);
    }
  }

  @Override
  public List<RankingEntryDto> getRanking(GameMode mode, int limit) throws TransportException {
    requireOpen();
    try {
      GetRankingRequest request =
          GetRankingRequest.newBuilder()
              .setMode(ProtoMapper.gameMode(DtoMapper.mode(mode)))
              .setLimit(limit)
              .build();
      return rpc(() -> blocking.getRanking(request)).getEntriesList().stream()
          .map(ProtoMapper::rankingEntry)
          .toList();
    } catch (StatusRuntimeException e) {
      throw failure("falha ao consultar ranking: " + statusMessage(e), e);
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
    closing = true;
    stopAccessKeepalive();
    try {
      if (blocking != null && token != null) {
        blocking.leave(tokenRequest());
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

  private TokenRequest tokenRequest() {
    return TokenRequest.newBuilder().setToken(token).build();
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
      } else if (closing) {
        LOG.debug("stream de eventos encerrado pelo cliente");
      } else if (accountCredentials.source() == AccountCredentials.Source.MSS_IDENTITY
          && GrpcErrors.isUnauthenticated(t)) {
        // M1: acesso de jogo vencido/revogado no meio da partida — reabre com acesso renovado.
        LOG.warn("stream de eventos recusou o acesso de jogo: {}", statusMessage(t));
        recoverStreamAccess();
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
