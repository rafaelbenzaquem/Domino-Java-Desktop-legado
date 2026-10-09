package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.GameEvent;
import br.com.mss.domino.net.GameEventListener;
import br.com.mss.domino.net.GameTransport;
import br.com.mss.domino.net.dto.EndDto;
import br.com.mss.domino.net.dto.GameModeDto;
import br.com.mss.domino.net.dto.HandDto;
import br.com.mss.domino.net.dto.LineDto;
import br.com.mss.domino.net.dto.MatchInfoDto;
import br.com.mss.domino.net.dto.MoveDto;
import br.com.mss.domino.net.dto.PlayerStatsDto;
import br.com.mss.domino.net.dto.RankingEntryDto;
import br.com.mss.domino.net.dto.SeatConnectionStatus;
import br.com.mss.domino.net.dto.SeatView;
import br.com.mss.domino.net.dto.SnapshotDto;
import br.com.mss.domino.net.dto.TileDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O {@link MatchController} com EDT síncrona: aplica eventos na projeção e re-emite (ADR-0016). */
class MatchControllerTest {

  private static SnapshotDto lobbySnapshot() {
    return new SnapshotDto(
        "m1",
        GameModeDto.FREE_FOR_ALL,
        "IN_PROGRESS",
        List.of(
            new SeatView(0, "eu", SeatConnectionStatus.ACTIVE, 7, false),
            new SeatView(1, "outro", SeatConnectionStatus.ACTIVE, 7, false)),
        LineDto.empty(),
        0,
        14,
        new TileDto(6, 6),
        0,
        new HandDto(List.of(new TileDto(6, 6), new TileDto(3, 4))),
        null);
  }

  @Test
  void join_setsInitialViewAndEmitsOnce() throws Exception {
    FakeTransport transport = new FakeTransport(lobbySnapshot());
    ClientEventBus bus = new ClientEventBus();
    List<ClientGameView> seen = new ArrayList<>();
    bus.subscribe((event, view) -> seen.add(view));
    MatchController controller = new MatchController(transport, bus, Runnable::run);

    controller.join("m1", "eu");

    assertNotNull(controller.view());
    assertEquals(1, seen.size());
    assertEquals(0, controller.view().mySeat());
  }

  @Test
  void event_appliesToProjectionAndNotifiesObservers() throws Exception {
    FakeTransport transport = new FakeTransport(lobbySnapshot());
    ClientEventBus bus = new ClientEventBus();
    List<GameEvent> events = new ArrayList<>();
    bus.subscribe((event, view) -> events.add(event));
    MatchController controller = new MatchController(transport, bus, Runnable::run);
    controller.join("m1", "eu");

    transport.fire(new GameEvent.TurnStarted(1, true));

    assertEquals(1, controller.view().currentSeat());
    assertSame(events.get(events.size() - 1).getClass(), GameEvent.TurnStarted.class);
  }

  @Test
  void eventsBeforeJoin_areBufferedThenApplied() throws Exception {
    FakeTransport transport = new FakeTransport(lobbySnapshot());
    MatchController controller =
        new MatchController(transport, new ClientEventBus(), Runnable::run);

    transport.fire(new GameEvent.TurnStarted(1, true)); // antes do join
    assertNull(controller.view());

    controller.join("m1", "eu");
    assertEquals(1, controller.view().currentSeat(), "o evento pendente foi aplicado");
  }

  @Test
  void play_sendsAMoveForMySeat() throws Exception {
    FakeTransport transport = new FakeTransport(lobbySnapshot());
    MatchController controller =
        new MatchController(transport, new ClientEventBus(), Runnable::run);
    controller.join("m1", "eu");

    controller.play(new TileDto(6, 6), EndDto.LEFT);

    assertEquals(1, transport.moves.size());
    assertEquals(MoveDto.play(0, new TileDto(6, 6), EndDto.LEFT), transport.moves.get(0));
  }

  @Test
  void join_semTokenStore_naoLanca() throws Exception {
    FakeTransport transport = new FakeTransport(lobbySnapshot());
    MatchController controller =
        new MatchController(transport, new ClientEventBus(), Runnable::run);

    controller.join("m1", "eu"); // não deve lançar mesmo sem tokenStore (edt=null default)

    assertNotNull(controller.view());
  }

  @Test
  void join_comTokenStore_salvaOTokenPorMatchIdEAssento() throws Exception {
    FakeTransport transport = new FakeTransport(lobbySnapshot());
    FakeSessionTokenStore tokenStore = new FakeSessionTokenStore();
    MatchController controller =
        new MatchController(transport, new ClientEventBus(), Runnable::run, tokenStore);

    controller.join("m1", "eu");

    assertEquals("tok", tokenStore.find("m1", 0).orElseThrow());
  }

  @Test
  void reconnect_comTokenStore_salvaOTokenUsandoOMatchIdDoSnapshot() throws Exception {
    FakeTransport transport = new FakeTransport(lobbySnapshot());
    FakeSessionTokenStore tokenStore = new FakeSessionTokenStore();
    MatchController controller =
        new MatchController(transport, new ClientEventBus(), Runnable::run, tokenStore);

    controller.reconnect("tok-antigo");

    assertEquals("tok-antigo", tokenStore.find("m1", 0).orElseThrow());
  }

  @Test
  void getMyStats_e_getRanking_delegamAoTransporteComOsParametrosRecebidos() throws Exception {
    FakeTransport transport = new FakeTransport(lobbySnapshot());
    MatchController controller =
        new MatchController(transport, new ClientEventBus(), Runnable::run);

    PlayerStatsDto stats = controller.getMyStats("guest-1", GameMode.FREE_FOR_ALL);
    List<RankingEntryDto> ranking = controller.getRanking(GameMode.PARTNERSHIP, 20);

    assertEquals(new PlayerStatsDto(3, 2, 1, 0), stats);
    assertEquals("guest-1", transport.lastStatsGuestId);
    assertEquals(GameMode.FREE_FOR_ALL, transport.lastStatsMode);
    assertEquals(1, ranking.size());
    assertEquals(GameMode.PARTNERSHIP, transport.lastRankingMode);
    assertEquals(20, transport.lastRankingLimit);
  }

  /** {@link SessionTokenStore} de mentira, em memória. */
  private static final class FakeSessionTokenStore implements SessionTokenStore {
    private final Map<String, String> saved = new HashMap<>();

    @Override
    public Optional<String> find(String matchId, int seat) {
      return Optional.ofNullable(saved.get(matchId + "|" + seat));
    }

    @Override
    public void save(String matchId, int seat, String token) {
      saved.put(matchId + "|" + seat, token);
    }

    @Override
    public void remove(String matchId, int seat) {
      saved.remove(matchId + "|" + seat);
    }
  }

  /** Transporte de mentira: guarda o listener e deixa o teste injetar eventos. */
  private static final class FakeTransport implements GameTransport {

    private final SnapshotDto joinSnapshot;
    private final List<MoveDto> moves = new ArrayList<>();
    private final List<String> chats = new ArrayList<>();
    private GameEventListener listener;

    FakeTransport(SnapshotDto joinSnapshot) {
      this.joinSnapshot = joinSnapshot;
    }

    void fire(GameEvent event) {
      listener.onEvent(event);
    }

    @Override
    public void open() {}

    @Override
    public List<MatchInfoDto> listMatches() {
      return List.of();
    }

    @Override
    public String createMatch(
        String name, GameMode mode, int size, int turnSeconds, int timeBankSeconds) {
      return "m1";
    }

    @Override
    public Session join(String matchId, String name) {
      return new Session("tok", joinSnapshot.mySeat(), joinSnapshot);
    }

    @Override
    public Session reconnect(String token) {
      return new Session(token, joinSnapshot.mySeat(), joinSnapshot);
    }

    @Override
    public void takeChair(int chair) {}

    @Override
    public void leaveChair() {}

    @Override
    public void setReady(boolean ready) {}

    @Override
    public void start() {}

    @Override
    public void sendMove(MoveDto move) {
      moves.add(move);
    }

    @Override
    public void sendChat(String text) {
      chats.add(text);
    }

    @Override
    public void requestElimination(int targetSeat) {}

    @Override
    public void confirmElimination() {}

    @Override
    public void declineElimination() {}

    @Override
    public void requestAbort() {}

    @Override
    public void confirmAbort() {}

    @Override
    public void declineAbort() {}

    private String lastStatsGuestId;
    private GameMode lastStatsMode;
    private GameMode lastRankingMode;
    private int lastRankingLimit;

    @Override
    public PlayerStatsDto getMyStats(String guestId, GameMode mode) {
      lastStatsGuestId = guestId;
      lastStatsMode = mode;
      return new PlayerStatsDto(3, 2, 1, 0);
    }

    @Override
    public List<RankingEntryDto> getRanking(GameMode mode, int limit) {
      lastRankingMode = mode;
      lastRankingLimit = limit;
      return List.of(new RankingEntryDto(1, "guest-1", "Ana", 3, 2, 1, 0));
    }

    @Override
    public void addListener(GameEventListener l) {
      this.listener = l;
    }

    @Override
    public void removeListener(GameEventListener l) {
      this.listener = null;
    }

    @Override
    public void close() {}
  }
}
