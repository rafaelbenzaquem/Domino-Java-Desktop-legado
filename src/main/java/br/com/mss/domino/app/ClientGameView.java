package br.com.mss.domino.app;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.domain.Line;
import br.com.mss.domino.domain.Seat;
import br.com.mss.domino.domain.Team;
import br.com.mss.domino.net.DtoMapper;
import br.com.mss.domino.net.GameEvent;
import br.com.mss.domino.net.dto.HandDto;
import br.com.mss.domino.net.dto.LineDto;
import br.com.mss.domino.net.dto.OutcomeDto;
import br.com.mss.domino.net.dto.SeatConnectionStatus;
import br.com.mss.domino.net.dto.SeatView;
import br.com.mss.domino.net.dto.SnapshotDto;
import br.com.mss.domino.net.dto.TileDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Projeção somente-leitura do estado da partida do lado do cliente (ADR-0002): construída a partir
 * do {@link SnapshotDto} e atualizada aplicando {@link GameEvent}. Nunca roda regra — a única
 * consulta ao domínio é {@link #canPlay}, que reaproveita {@code Line.fitsEither}.
 *
 * @param seats no lobby de duplas, as 4 cadeiras (com "pronto"); em jogo, os assentos
 * @param standing jogadores no lobby de duplas ainda sem cadeira
 * @param startable a condição de início do modo já foi satisfeita?
 * @param turnDeadline verdade do servidor (epoch millis) para o *countdown* da vez atual
 *     (ADR-0012); {@code 0} quando não há relógio armado (fora de jogo, ou compra forçada em
 *     andamento)
 * @param turnBankRemaining banco (segundos) de quem está na vez, no momento em que o relógio armou
 *     — só para exibição, não é ao vivo
 * @param bankBySeat último banco conhecido de cada assento (atualizado toda vez que o relógio dele
 *     arma); serve para mostrar "seu banco" mesmo fora da sua vez
 */
public record ClientGameView(
    String matchId,
    GameMode mode,
    Phase phase,
    List<SeatView> seats,
    List<String> standing,
    boolean startable,
    int mySeat,
    LineDto line,
    TileDto openingTile,
    int currentSeat,
    int boneyardCount,
    HandDto myHand,
    OutcomeDto outcome,
    long turnDeadline,
    int turnBankRemaining,
    Map<Integer, Integer> bankBySeat) {

  public enum Phase {
    LOBBY,
    IN_PROGRESS,
    FINISHED
  }

  public ClientGameView {
    seats = List.copyOf(seats);
    standing = List.copyOf(standing);
    bankBySeat = Map.copyOf(bankBySeat);
  }

  public static ClientGameView from(SnapshotDto s) {
    return new ClientGameView(
        s.matchId(),
        DtoMapper.mode(s.mode()),
        Phase.valueOf(s.phase()),
        s.seats(),
        List.of(),
        false,
        s.mySeat(),
        s.line(),
        s.openingTile(),
        s.currentSeat(),
        s.boneyardCount(),
        s.myHand(),
        s.outcome(),
        0,
        0,
        Map.of());
  }

  /** Novo estado após um evento do servidor. */
  public ClientGameView apply(GameEvent event) {
    return switch (event) {
      case GameEvent.LobbyUpdated e ->
          new ClientGameView(
              matchId,
              mode,
              Phase.LOBBY,
              e.chairs(),
              e.standing(),
              e.startable(),
              mySeat,
              line,
              openingTile,
              currentSeat,
              boneyardCount,
              myHand,
              outcome,
              0,
              0,
              bankBySeat);
      case GameEvent.MatchStarted e -> from(e.snapshot());
      case GameEvent.TurnStarted e -> withCurrent(e.seat());
      case GameEvent.ClockStarted e -> withClock(e.seat(), e.deadline(), e.bankRemainingSeconds());
      case GameEvent.MoveApplied e -> applyMove(e);
      case GameEvent.TileBought e -> applyBuy(e);
      case GameEvent.TurnPassed e -> this;
      case GameEvent.ChatPosted e -> this;
      case GameEvent.MatchEnded e -> withOutcome(e.outcome());
      case GameEvent.PlayerDisconnected e ->
          withSeatStatus(e.seat(), SeatConnectionStatus.DISCONNECTED);
      case GameEvent.PlayerReconnected e -> withSeatStatus(e.seat(), SeatConnectionStatus.ACTIVE);
      case GameEvent.PlayerEliminated e ->
          withSeatStatus(e.seat(), SeatConnectionStatus.ELIMINATED);
      case GameEvent.EliminationRequested e -> this;
      case GameEvent.EliminationCancelled e -> this;
      case GameEvent.AbortRequested e -> this;
      case GameEvent.AbortCancelled e -> this;
      case GameEvent.RematchRequested e -> this;
      case GameEvent.RematchDeclined e -> this;
      case GameEvent.RematchStarted e -> this;
    };
  }

  /** Atualização otimista de UI ao sentar/levantar no lobby de duplas (o servidor confirma). */
  public ClientGameView withMySeat(int seat) {
    return copy(
        phase,
        seats,
        startable,
        seat,
        line,
        boneyardCount,
        myHand,
        outcome,
        currentSeat,
        turnDeadline,
        turnBankRemaining,
        bankBySeat);
  }

  private ClientGameView withCurrent(int seat) {
    // zera o relógio: ou a compra forçada está em andamento (sem clock), ou o ClockStarted que
    // acompanha esta vez chega em seguida, na mesma leva de eventos.
    return copy(
        phase,
        seats,
        startable,
        mySeat,
        line,
        boneyardCount,
        myHand,
        outcome,
        seat,
        0,
        0,
        bankBySeat);
  }

  private ClientGameView withClock(int seat, long deadline, int bankRemaining) {
    Map<Integer, Integer> nextBank = new LinkedHashMap<>(bankBySeat);
    nextBank.put(seat, bankRemaining);
    return copy(
        phase,
        seats,
        startable,
        mySeat,
        line,
        boneyardCount,
        myHand,
        outcome,
        currentSeat,
        deadline,
        bankRemaining,
        nextBank);
  }

  private ClientGameView withOutcome(OutcomeDto o) {
    return copy(
        Phase.FINISHED,
        seats,
        startable,
        mySeat,
        line,
        boneyardCount,
        myHand,
        o,
        currentSeat,
        0,
        0,
        bankBySeat);
  }

  private ClientGameView applyMove(GameEvent.MoveApplied e) {
    HandDto hand = myHand;
    if (e.seat() == mySeat && e.tile() != null) {
      hand = removeTile(myHand, e.tile());
    }
    return copy(
        phase,
        withHandCount(e.seat(), e.handCount()),
        startable,
        mySeat,
        e.line(),
        boneyardCount,
        hand,
        outcome,
        currentSeat,
        turnDeadline,
        turnBankRemaining,
        bankBySeat);
  }

  private ClientGameView applyBuy(GameEvent.TileBought e) {
    HandDto hand = myHand;
    if (e.seat() == mySeat && e.tile() != null) {
      List<TileDto> tiles = new ArrayList<>(myHand.tiles());
      tiles.add(e.tile());
      hand = new HandDto(tiles);
    }
    int newCount = seatOf(e.seat()).map(SeatView::handCount).orElse(0) + 1;
    return copy(
        phase,
        withHandCount(e.seat(), newCount),
        startable,
        mySeat,
        line,
        e.boneyardCount(),
        hand,
        outcome,
        currentSeat,
        turnDeadline,
        turnBankRemaining,
        bankBySeat);
  }

  /** Situação de um assento mudou. */
  private ClientGameView withSeatStatus(int seat, SeatConnectionStatus status) {
    List<SeatView> next = new ArrayList<>(seats.size());
    for (SeatView s : seats) {
      next.add(
          s.seat() == seat
              ? new SeatView(s.seat(), s.name(), status, s.handCount(), s.ready())
              : s);
    }
    return copy(
        phase,
        next,
        startable,
        mySeat,
        line,
        boneyardCount,
        myHand,
        outcome,
        currentSeat,
        turnDeadline,
        turnBankRemaining,
        bankBySeat);
  }

  // --- consultas para a UI ---

  public boolean isMyTurn() {
    return phase == Phase.IN_PROGRESS && currentSeat == mySeat;
  }

  public boolean isFinished() {
    return phase == Phase.FINISHED;
  }

  public Optional<SeatView> seatOf(int seat) {
    return seats.stream().filter(s -> s.seat() == seat).findFirst();
  }

  public String nameOf(int seat) {
    return seatOf(seat).map(SeatView::name).filter(n -> !n.isEmpty()).orElse("?");
  }

  /** Dupla de um assento (só faz sentido no modo de duplas). */
  public Team teamOf(int seat) {
    return Team.of(Seat.of(seat));
  }

  /** Tenho alguma jogada legal na mão? (se não, no modo de duplas preciso passar.) */
  public boolean canPlayAny() {
    return myHand.tiles().stream().anyMatch(this::canPlay);
  }

  /** A peça encaixa em alguma ponta da mesa? (mesa vazia: só a peça de abertura.) */
  public boolean canPlay(TileDto tile) {
    Line l = DtoMapper.line(line);
    if (l.isEmpty()) {
      return openingTile != null && openingTile.equals(tile);
    }
    return l.fitsEither(DtoMapper.tile(tile));
  }

  /** Há um relógio armado agora para a vez atual? */
  public boolean hasActiveClock() {
    return phase == Phase.IN_PROGRESS && turnDeadline > 0;
  }

  /** Último banco conhecido de um assento (segundos), ou {@code -1} se ainda não apareceu. */
  public int bankOf(int seat) {
    return bankBySeat.getOrDefault(seat, -1);
  }

  private List<SeatView> withHandCount(int seat, int handCount) {
    List<SeatView> next = new ArrayList<>(seats.size());
    for (SeatView s : seats) {
      next.add(
          s.seat() == seat
              ? new SeatView(s.seat(), s.name(), s.status(), handCount, s.ready())
              : s);
    }
    return next;
  }

  private static HandDto removeTile(HandDto hand, TileDto tile) {
    List<TileDto> tiles = new ArrayList<>(hand.tiles());
    tiles.remove(tile);
    return new HandDto(tiles);
  }

  private ClientGameView copy(
      Phase phase,
      List<SeatView> seats,
      boolean startable,
      int mySeat,
      LineDto line,
      int boneyardCount,
      HandDto myHand,
      OutcomeDto outcome,
      int currentSeat,
      long turnDeadline,
      int turnBankRemaining,
      Map<Integer, Integer> bankBySeat) {
    return new ClientGameView(
        matchId,
        mode,
        phase,
        seats,
        standing,
        startable,
        mySeat,
        line,
        openingTile,
        currentSeat,
        boneyardCount,
        myHand,
        outcome,
        turnDeadline,
        turnBankRemaining,
        bankBySeat);
  }
}
