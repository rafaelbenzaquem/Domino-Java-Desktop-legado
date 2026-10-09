package br.com.mss.domino.app;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.GameEvent;
import br.com.mss.domino.net.GameEventListener;
import br.com.mss.domino.net.GameTransport;
import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.dto.EndDto;
import br.com.mss.domino.net.dto.MatchInfoDto;
import br.com.mss.domino.net.dto.MoveDto;
import br.com.mss.domino.net.dto.PlayerStatsDto;
import br.com.mss.domino.net.dto.RankingEntryDto;
import br.com.mss.domino.net.dto.SnapshotDto;
import br.com.mss.domino.net.dto.TileDto;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Único assinante "de baixo" (ADR-0016): recebe {@link GameEvent} da rede, <b>entrega na EDT</b>
 * (ADR-0010), mantém a projeção de {@link ClientGameView} e re-emite para os observadores de UI
 * pelo {@link ClientEventBus}. Também é a única porta da UI para pedir jogadas/chat — a UI nunca
 * toca no {@link GameTransport}.
 */
public final class MatchController implements GameEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(MatchController.class);

  private final GameTransport transport;
  private final ClientEventBus bus;
  private final Consumer<Runnable> edt;
  private final SessionTokenStore tokenStore;

  private final List<GameEvent> pending = new ArrayList<>();
  private volatile ClientGameView view;

  public MatchController(GameTransport transport, ClientEventBus bus) {
    this(transport, bus, (SessionTokenStore) null);
  }

  /**
   * {@code tokenStore}, se não nulo, grava sozinho o {@code session_token} a cada {@link
   * #join}/{@link #reconnect} bem-sucedido (ADR-0019) — sem isso, o caminho de retomada automática
   * da {@code MatchListCard} nunca acha nada salvo.
   */
  public MatchController(
      GameTransport transport, ClientEventBus bus, SessionTokenStore tokenStore) {
    this(transport, bus, SwingUtilities::invokeLater, tokenStore);
  }

  /** {@code edt} injetável para testes ({@code Runnable::run}); sem {@code tokenStore}. */
  MatchController(GameTransport transport, ClientEventBus bus, Consumer<Runnable> edt) {
    this(transport, bus, edt, null);
  }

  MatchController(
      GameTransport transport,
      ClientEventBus bus,
      Consumer<Runnable> edt,
      SessionTokenStore tokenStore) {
    this.transport = transport;
    this.bus = bus;
    this.edt = edt;
    this.tokenStore = tokenStore;
    transport.addListener(this);
  }

  // --- fora de partida ---

  public void open() throws TransportException {
    transport.open();
  }

  public List<MatchInfoDto> listMatches() throws TransportException {
    return transport.listMatches();
  }

  // --- estatísticas e ranking (M5-5, ADR-0029) ---

  public PlayerStatsDto getMyStats(String guestId, GameMode mode) throws TransportException {
    return transport.getMyStats(guestId, mode);
  }

  public List<RankingEntryDto> getRanking(GameMode mode, int limit) throws TransportException {
    return transport.getRanking(mode, limit);
  }

  public String createMatch(
      String name, GameMode mode, int size, int turnSeconds, int timeBankSeconds)
      throws TransportException {
    return createMatch(name, mode, size, turnSeconds, timeBankSeconds, "");
  }

  /** Como acima, com {@code password} (Fase 4-1, ADR-0014) — vazia deixa a partida aberta. */
  public String createMatch(
      String name, GameMode mode, int size, int turnSeconds, int timeBankSeconds, String password)
      throws TransportException {
    return transport.createMatch(name, mode, size, turnSeconds, timeBankSeconds, password);
  }

  /** Entra na partida (sem senha) e passa a projetar o estado dela. */
  public GameTransport.Session join(String matchId, String name) throws TransportException {
    return join(matchId, name, "");
  }

  /**
   * Como acima, com {@code password} (Fase 4-1, ADR-0014) — vazia se a partida for aberta ou você
   * não tiver uma; recusada (via {@link TransportException}) se não bater com a da criação.
   */
  public GameTransport.Session join(String matchId, String name, String password)
      throws TransportException {
    GameTransport.Session session = transport.join(matchId, name, password);
    LOG.info("JOINED match={} seat={} as {}", matchId, session.seat(), name);
    seedView(session.snapshot());
    saveToken(matchId, session);
    return session;
  }

  /**
   * Reconecta pelo {@code session_token} de uma sessão anterior (ADR-0008, ADR-0013) e passa a
   * projetar o estado atual dela.
   */
  public GameTransport.Session reconnect(String token) throws TransportException {
    GameTransport.Session session = transport.reconnect(token);
    LOG.info("RECONNECTED seat={}", session.seat());
    seedView(session.snapshot());
    saveToken(session.snapshot().matchId(), session);
    return session;
  }

  /**
   * ADR-0019: sem {@code tokenStore} (testes, cenários que não precisam de retomada), não faz nada.
   */
  private void saveToken(String matchId, GameTransport.Session session) {
    if (tokenStore != null) {
      tokenStore.save(matchId, session.seat(), session.token());
    }
  }

  private void seedView(SnapshotDto snapshot) {
    edt.accept(
        () -> {
          synchronized (this) {
            view = ClientGameView.from(snapshot);
            for (GameEvent buffered : pending) {
              view = view.apply(buffered);
            }
            pending.clear();
          }
          bus.emit(null, view);
        });
  }

  // --- lobby de duplas ---

  public void takeChair(int chair) throws TransportException {
    transport.takeChair(chair);
    optimisticSeat(chair);
  }

  public void leaveChair() throws TransportException {
    transport.leaveChair();
    optimisticSeat(-1);
  }

  public void setReady(boolean ready) throws TransportException {
    transport.setReady(ready);
  }

  private void optimisticSeat(int chair) {
    edt.accept(
        () -> {
          if (view != null) {
            view = view.withMySeat(chair);
            bus.emit(null, view);
          }
        });
  }

  // --- em partida ---

  public void startMatch() throws TransportException {
    transport.start();
  }

  public void play(TileDto tile, EndDto end) throws TransportException {
    LOG.info("PLAY seat={} tile={} end={}", view.mySeat(), tile, end);
    transport.sendMove(MoveDto.play(view.mySeat(), tile, end));
  }

  /** Passe voluntário (modo de duplas, quando não há jogada). */
  public void pass() throws TransportException {
    LOG.info("PASS seat={}", view.mySeat());
    transport.sendMove(MoveDto.pass(view.mySeat()));
  }

  public void chat(String text) throws TransportException {
    LOG.debug("CHAT send: {}", text);
    transport.sendChat(text);
  }

  // --- resiliência (ADR-0013) ---

  public void requestElimination(int targetSeat) throws TransportException {
    transport.requestElimination(targetSeat);
  }

  public void confirmElimination() throws TransportException {
    transport.confirmElimination();
  }

  public void declineElimination() throws TransportException {
    transport.declineElimination();
  }

  public void requestAbort() throws TransportException {
    transport.requestAbort();
  }

  public void confirmAbort() throws TransportException {
    transport.confirmAbort();
  }

  public void declineAbort() throws TransportException {
    transport.declineAbort();
  }

  // --- revanche e retorno à lista (Fase 4.5-4, ADR-0024) ---

  /**
   * Sai da partida terminada sem fechar a sessão de rede — dá pra buscar/criar outra em seguida.
   */
  public void leaveMatch() {
    transport.leaveMatch();
  }

  public void requestRematch() throws TransportException {
    transport.requestRematch();
  }

  public void confirmRematch() throws TransportException {
    transport.confirmRematch();
  }

  public void declineRematch() throws TransportException {
    transport.declineRematch();
  }

  public ClientGameView view() {
    return view;
  }

  public void close() {
    transport.removeListener(this);
    transport.close();
  }

  @Override
  public void onEvent(GameEvent event) {
    LOG.debug("RECV {}", event);
    edt.accept(
        () -> {
          ClientGameView emitted;
          synchronized (this) {
            if (view == null) {
              pending.add(event);
              LOG.trace("buffered (sem view ainda): {}", event);
              return;
            }
            view = view.apply(event);
            emitted = view;
          }
          LOG.trace(
              "APPLIED {} -> phase={} currentSeat={}",
              event.getClass().getSimpleName(),
              emitted.phase(),
              emitted.currentSeat());
          bus.emit(event, emitted);
        });
  }
}
