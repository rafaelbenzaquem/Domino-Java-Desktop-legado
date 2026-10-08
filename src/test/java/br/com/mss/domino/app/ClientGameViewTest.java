package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.GameEvent;
import br.com.mss.domino.net.dto.EndDto;
import br.com.mss.domino.net.dto.EndReasonDto;
import br.com.mss.domino.net.dto.GameModeDto;
import br.com.mss.domino.net.dto.HandDto;
import br.com.mss.domino.net.dto.LineDto;
import br.com.mss.domino.net.dto.OutcomeDto;
import br.com.mss.domino.net.dto.SeatConnectionStatus;
import br.com.mss.domino.net.dto.SeatView;
import br.com.mss.domino.net.dto.SnapshotDto;
import br.com.mss.domino.net.dto.TileDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Projeção do estado no cliente a partir de snapshot + eventos, sem rede nem Swing. */
class ClientGameViewTest {

  private static TileDto t(int a, int b) {
    return new TileDto(a, b);
  }

  private static SnapshotDto snapshot() {
    return new SnapshotDto(
        "m1",
        GameModeDto.FREE_FOR_ALL,
        "IN_PROGRESS",
        List.of(
            new SeatView(0, "Ana", SeatConnectionStatus.ACTIVE, 7, false),
            new SeatView(1, "Beto", SeatConnectionStatus.ACTIVE, 7, false)),
        LineDto.empty(),
        0,
        14,
        t(6, 6),
        0,
        new HandDto(List.of(t(6, 6), t(6, 4), t(2, 3))),
        null);
  }

  private static SnapshotDto lobbySnapshot() {
    return new SnapshotDto(
        "m1",
        GameModeDto.PARTNERSHIP,
        "LOBBY",
        List.of(),
        LineDto.empty(),
        -1,
        0,
        null,
        -1,
        HandDto.empty(),
        null);
  }

  @Test
  void lobbyUpdated_projectsChairsStandingAndStartable() {
    ClientGameView v = ClientGameView.from(lobbySnapshot());
    v =
        v.apply(
            new GameEvent.LobbyUpdated(
                GameModeDto.PARTNERSHIP,
                List.of(
                    new SeatView(0, "Ana", SeatConnectionStatus.ACTIVE, 0, true),
                    new SeatView(1, "", SeatConnectionStatus.ACTIVE, 0, false),
                    new SeatView(2, "Beto", SeatConnectionStatus.ACTIVE, 0, false),
                    new SeatView(3, "", SeatConnectionStatus.ACTIVE, 0, false)),
                List.of("Caio"),
                false));

    assertEquals(ClientGameView.Phase.LOBBY, v.phase());
    assertEquals(4, v.seats().size());
    assertTrue(v.seats().get(0).ready());
    assertFalse(v.seats().get(1).occupied());
    assertEquals(List.of("Caio"), v.standing());
    assertFalse(v.startable());
  }

  @Test
  void withMySeat_updatesOptimistically() {
    ClientGameView v = ClientGameView.from(lobbySnapshot()).withMySeat(2);
    assertEquals(2, v.mySeat());
  }

  @Test
  void fromSnapshot_readsEverything() {
    ClientGameView v = ClientGameView.from(snapshot());
    assertEquals(0, v.mySeat());
    assertEquals(0, v.currentSeat());
    assertEquals(14, v.boneyardCount());
    assertEquals(ClientGameView.Phase.IN_PROGRESS, v.phase());
    assertTrue(v.isMyTurn());
    assertEquals("Beto", v.nameOf(1));
  }

  @Test
  void emptyLine_onlyTheOpeningTileIsPlayable() {
    ClientGameView v = ClientGameView.from(snapshot());
    assertTrue(v.canPlay(t(6, 6)));
    assertFalse(v.canPlay(t(6, 4)));
  }

  @Test
  void moveApplied_updatesLine_countsAndOwnHand() {
    ClientGameView v = ClientGameView.from(snapshot());
    LineDto afterOpen = new LineDto(List.of(t(6, 6)), 6, 6);

    v = v.apply(new GameEvent.MoveApplied(0, t(6, 6), EndDto.LEFT, afterOpen, 6));

    assertEquals(6, v.seatOf(0).orElseThrow().handCount());
    assertFalse(v.myHand().tiles().contains(t(6, 6)), "a peça saiu da minha mão");
    assertTrue(v.canPlay(t(6, 4)), "agora 6-4 encaixa na ponta 6");
    assertFalse(v.canPlay(t(2, 3)));
  }

  @Test
  void turnStarted_movesTheTurn() {
    ClientGameView v = ClientGameView.from(snapshot()).apply(new GameEvent.TurnStarted(1, true));
    assertEquals(1, v.currentSeat());
    assertFalse(v.isMyTurn());
  }

  @Test
  void clockStarted_setsTheActiveClockAndRemembersTheSeatsBank() {
    ClientGameView v = ClientGameView.from(snapshot());
    assertEquals(-1, v.bankOf(0), "banco desconhecido antes do 1º relógio dele");

    long deadline = System.currentTimeMillis() + 45_000;
    v = v.apply(new GameEvent.ClockStarted(0, deadline, 30));

    assertTrue(v.hasActiveClock());
    assertEquals(deadline, v.turnDeadline());
    assertEquals(30, v.turnBankRemaining());
    assertEquals(30, v.bankOf(0), "banco do assento 0 fica registrado");
  }

  @Test
  void turnStarted_clearsTheActiveClock_butKeepsRememberedBanks() {
    ClientGameView v =
        ClientGameView.from(snapshot())
            .apply(new GameEvent.ClockStarted(0, System.currentTimeMillis() + 10_000, 30));

    v = v.apply(new GameEvent.TurnStarted(1, true));

    assertFalse(v.hasActiveClock(), "relógio zera até o próximo ClockStarted chegar");
    assertEquals(30, v.bankOf(0), "banco lembrado do assento 0 não se perde");
  }

  @Test
  void tileBought_growsOwnHandAndSetsBoneyard() {
    ClientGameView v = ClientGameView.from(snapshot());
    v = v.apply(new GameEvent.TileBought(0, t(1, 1), 13));
    assertEquals(13, v.boneyardCount());
    assertEquals(8, v.seatOf(0).orElseThrow().handCount());
    assertTrue(v.myHand().tiles().contains(t(1, 1)));
  }

  @Test
  void tileBought_forSomeoneElse_onlyBumpsTheirCount() {
    ClientGameView v = ClientGameView.from(snapshot());
    v = v.apply(new GameEvent.TileBought(1, null, 13));
    assertEquals(8, v.seatOf(1).orElseThrow().handCount());
    assertEquals(3, v.myHand().tiles().size(), "minha mão não muda");
  }

  @Test
  void matchEnded_marksFinishedWithOutcome() {
    OutcomeDto outcome =
        new OutcomeDto(EndReasonDto.DOMINO, List.of(0), null, Map.of(0, 0, 1, 9), false);
    ClientGameView v = ClientGameView.from(snapshot()).apply(new GameEvent.MatchEnded(outcome));
    assertTrue(v.isFinished());
    assertEquals(outcome, v.outcome());
  }
}
