package br.com.mss.domino.net.grpc;

import static br.com.mss.domino.domain.GameMode.FREE_FOR_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import br.com.mss.domino.domain.End;
import br.com.mss.domino.domain.GameState;
import br.com.mss.domino.domain.Hand;
import br.com.mss.domino.domain.Line;
import br.com.mss.domino.domain.Seat;
import br.com.mss.domino.domain.Tile;
import br.com.mss.domino.net.DtoMapper;
import br.com.mss.domino.net.GameEvent;
import br.com.mss.domino.net.GameEventListener;
import br.com.mss.domino.net.GameTransport;
import br.com.mss.domino.net.MatchService;
import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.dto.ClockSettings;
import br.com.mss.domino.net.dto.EndReasonDto;
import br.com.mss.domino.net.dto.MoveDto;
import br.com.mss.domino.net.dto.OutcomeDto;
import br.com.mss.domino.net.dto.PassReason;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Host + dois clientes ponta a ponta sobre gRPC real (porta TCP efêmera) — Fase 6a-3. Mesmos
 * cenários do {@code RmiTransportTest}: o servidor é autoritativo (jogada ilegal recusada, sem
 * mudar estado — ADR-0002), os dois clientes veem o mesmo desfecho, e {@link GrpcClientTransport}
 * cumpre {@link GameTransport} igual à implementação RMI. As jogadas são escolhidas a partir do
 * estado do host, como no gêmeo RMI (a projeção do lado do cliente é da camada {@code app}).
 */
class GrpcTransportTest {

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static GrpcHostTransport newHost() throws IOException {
    GrpcHostTransport host = new GrpcHostTransport(freePort());
    host.start();
    return host;
  }

  private static GrpcClientTransport newClient(GrpcHostTransport host) {
    return new GrpcClientTransport("127.0.0.1", host.port());
  }

  /**
   * Primeira jogada legal de {@code seat} no estado atual (preferindo a ponta esquerda). {@code
   * net.Fixtures} não é acessível daqui (pacote-privada, em {@code br.com.mss.domino.net}) — mesma
   * lógica, duplicada, como já feito em {@code GrpcHostTransportTest}.
   */
  private static MoveDto firstPlayable(GameState state, Seat seat) {
    Hand hand = state.hand(seat);
    Line line = state.line();
    if (line.isEmpty()) {
      Tile opening = state.openingTile();
      if (hand.contains(opening)) {
        return MoveDto.play(seat.index(), DtoMapper.tile(opening), DtoMapper.end(End.LEFT));
      }
      throw new AssertionError(seat + " sem jogada e a partida não acabou");
    }
    for (Tile t : hand.tiles()) {
      if (line.fits(t, End.LEFT)) {
        return MoveDto.play(seat.index(), DtoMapper.tile(t), DtoMapper.end(End.LEFT));
      }
      if (line.fits(t, End.RIGHT)) {
        return MoveDto.play(seat.index(), DtoMapper.tile(t), DtoMapper.end(End.RIGHT));
      }
    }
    throw new AssertionError(seat + " sem jogada e a partida não acabou");
  }

  @Test
  void listMatches_showsAHostedMatch() throws Exception {
    try (GrpcHostTransport host = newHost()) {
      String id = host.hostMatch("Mesa 1", FREE_FOR_ALL, 2, 20L);

      GrpcClientTransport client = newClient(host);
      try {
        client.open();
        List<?> matches = client.listMatches();
        assertEquals(1, matches.size());
        assertEquals(id, ((br.com.mss.domino.net.dto.MatchInfoDto) matches.get(0)).matchId());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void twoClients_playAFullFreeForAll_andSeeTheSameOutcome() throws Exception {
    try (GrpcHostTransport host = newHost()) {
      String matchId = host.hostMatch("Mesa 1", FREE_FOR_ALL, 2, 20L);

      Recorder ra = new Recorder();
      Recorder rb = new Recorder();
      GrpcClientTransport ta = newClient(host);
      GrpcClientTransport tb = newClient(host);
      ta.addListener(ra);
      tb.addListener(rb);
      try {
        ta.open();
        tb.open();
        GameTransport.Session sa = ta.join(matchId, "Ana");
        tb.join(matchId, "Beto");

        ra.await(GameEvent.MatchStarted.class);
        rb.await(GameEvent.MatchStarted.class);

        MatchService match = host.match(matchId);
        int guard = 0;
        while (match.phase() != MatchService.Phase.FINISHED) {
          if (++guard > 500) {
            fail("partida não terminou");
          }
          GameState state = match.state();
          Seat cur = state.currentSeat();
          MoveDto move = firstPlayable(state, cur);
          GrpcClientTransport mover = cur.index() == sa.seat() ? ta : tb;
          mover.sendMove(move);
        }

        ra.await(GameEvent.MatchEnded.class);
        rb.await(GameEvent.MatchEnded.class);
        OutcomeDto fromA = ra.last(GameEvent.MatchEnded.class).outcome();
        OutcomeDto fromB = rb.last(GameEvent.MatchEnded.class).outcome();

        assertEquals(fromA, fromB);
        assertEquals(DtoMapper.outcome(match.state().outcome()), fromA);
        assertTrue(ra.all(GameEvent.MoveApplied.class).size() >= 2, "os clientes viram jogadas");
        assertEquals(
            ra.all(GameEvent.MoveApplied.class).size(),
            rb.all(GameEvent.MoveApplied.class).size(),
            "o mesmo número de jogadas para os dois");
      } finally {
        ta.close();
        tb.close();
      }
    }
  }

  @Test
  void hostRejectsAnOutOfTurnMove_withoutTouchingState() throws Exception {
    try (GrpcHostTransport host = newHost()) {
      String matchId = host.hostMatch("Mesa 1", FREE_FOR_ALL, 2, 20L);

      GrpcClientTransport ta = newClient(host);
      GrpcClientTransport tb = newClient(host);
      Recorder ra = new Recorder();
      Recorder rb = new Recorder();
      ta.addListener(ra);
      tb.addListener(rb);
      try {
        ta.open();
        tb.open();
        GameTransport.Session sa = ta.join(matchId, "Ana");
        tb.join(matchId, "Beto");
        ra.await(GameEvent.MatchStarted.class);
        rb.await(GameEvent.MatchStarted.class);

        MatchService match = host.match(matchId);
        GameState before = match.state();
        Seat current = before.currentSeat();
        GrpcClientTransport wrongMover = current.index() == sa.seat() ? tb : ta;

        // uma peça legal para o assento da vez, mas enviada pelo cliente errado
        MoveDto move = firstPlayable(before, current);

        TransportException ex =
            assertThrows(TransportException.class, () -> wrongMover.sendMove(move));
        assertTrue(
            ex.getMessage().contains("NOT_YOUR_TURN") || ex.getMessage().contains("recusada"),
            "mensagem indica recusa por regra: " + ex.getMessage());
        assertSame(before, match.state(), "estado do host não muda numa jogada recusada");
      } finally {
        ta.close();
        tb.close();
      }
    }
  }

  @Test
  void turnClock_reachesTheClientOverGrpc_andTimesOutWithoutAnyMove() throws Exception {
    try (GrpcHostTransport host = newHost()) {
      // relógio propositalmente curto: 1s por jogada, sem banco.
      String matchId = host.hostMatch("Mesa rápida", FREE_FOR_ALL, 2, 20L, new ClockSettings(1, 0));

      GrpcClientTransport ta = newClient(host);
      GrpcClientTransport tb = newClient(host);
      Recorder ra = new Recorder();
      Recorder rb = new Recorder();
      ta.addListener(ra);
      tb.addListener(rb);
      try {
        ta.open();
        tb.open();
        ta.join(matchId, "Ana");
        tb.join(matchId, "Beto");
        ra.await(GameEvent.MatchStarted.class);
        rb.await(GameEvent.MatchStarted.class);

        // ninguém joga: o relógio (visível dos dois lados, ADR-0016) estoura sozinho.
        GameEvent.ClockStarted clock = ra.await(GameEvent.ClockStarted.class);
        assertTrue(clock.deadline() > System.currentTimeMillis());

        GameEvent.TurnPassed timeout = ra.await(GameEvent.TurnPassed.class);
        assertEquals(PassReason.TIMEOUT, timeout.reason());
        // o mesmo evento chegou no outro cliente também.
        assertEquals(PassReason.TIMEOUT, rb.await(GameEvent.TurnPassed.class).reason());
      } finally {
        ta.close();
        tb.close();
      }
    }
  }

  @Test
  void disconnectReconnectAndElimination_workOverRealGrpc() throws Exception {
    try (GrpcHostTransport host = newHost()) {
      String matchId = host.hostMatch("Mesa 1", FREE_FOR_ALL, 2, 20L);

      GrpcClientTransport ta = newClient(host);
      GrpcClientTransport tb = newClient(host);
      Recorder ra = new Recorder();
      Recorder rb = new Recorder();
      ta.addListener(ra);
      tb.addListener(rb);
      try {
        ta.open();
        tb.open();
        GameTransport.Session sa = ta.join(matchId, "Ana");
        GameTransport.Session sb = tb.join(matchId, "Beto");
        ra.await(GameEvent.MatchStarted.class);
        rb.await(GameEvent.MatchStarted.class);

        // simula a queda de Ana: o próprio host marca o assento (a rede que detectaria isso de
        // verdade é a GrpcHostTransport, exercitada à parte pelo teste de cancelamento de stream
        // da 6a-2 — GrpcHostTransportTest).
        MatchService match = host.match(matchId);
        match.disconnect(sa.token()).get();
        rb.await(GameEvent.PlayerDisconnected.class);

        // Beto reconecta Ana pelo token dela num transporte novo (processo "reaberto").
        GrpcClientTransport reconnected = newClient(host);
        Recorder rr = new Recorder();
        reconnected.addListener(rr);
        try {
          reconnected.open();
          GameTransport.Session resumed = reconnected.reconnect(sa.token());
          assertEquals(sa.seat(), resumed.seat());
          rb.await(GameEvent.PlayerReconnected.class);

          // com os 2 conectados de novo, Beto não consegue eliminar ninguém.
          TransportException noOne =
              assertThrows(TransportException.class, () -> tb.requestElimination(sa.seat()));
          assertTrue(noOne.getMessage().toLowerCase().contains("não está caído"));

          // Ana cai de novo; como só sobra Beto conectado, o pedido dele já basta.
          match.disconnect(sa.token()).get();
          tb.requestElimination(sa.seat());

          GameEvent.MatchEnded ended = rb.await(GameEvent.MatchEnded.class);
          assertEquals(EndReasonDto.LAST_PLAYER_STANDING, ended.outcome().reason());
          assertEquals(List.of(sb.seat()), ended.outcome().winningSeats());
        } finally {
          reconnected.close();
        }
      } finally {
        ta.close();
        tb.close();
      }
    }
  }

  @Test
  void partidaTrancada_recusaSenhaErradaEAceitaACerta_pontaAPonta() throws Exception {
    // Fase 4-1 (ADR-0014): a senha em si nunca atravessa a rede, só o hash — este teste passa a
    // senha em claro pro GrpcClientTransport (como a UI faria) e prova que só o hash certo abre.
    try (GrpcHostTransport host = newHost()) {
      GrpcClientTransport creator = newClient(host);
      creator.open();
      String matchId;
      try {
        matchId = creator.createMatch("Mesa trancada", FREE_FOR_ALL, 2, 30, 120, "abacate123");
      } finally {
        creator.close();
      }

      assertTrue(
          host.match(matchId).info().hasPassword(), "a lista deveria marcar a partida como 🔒");

      GrpcClientTransport wrongPassword = newClient(host);
      try {
        wrongPassword.open();
        TransportException ex =
            assertThrows(
                TransportException.class,
                () -> wrongPassword.join(matchId, "Intruso", "senha-errada"));
        assertTrue(ex.getMessage().toLowerCase().contains("senha"));
      } finally {
        wrongPassword.close();
      }

      GrpcClientTransport rightPassword = newClient(host);
      try {
        rightPassword.open();
        GameTransport.Session session = rightPassword.join(matchId, "Ana", "abacate123");
        // Fase 4.5-5 (ADR-0025): cadeira sorteada no modo todos-contra-todos — só confere que
        // alguma cadeira válida foi dada, não mais sempre a 0.
        assertTrue(session.seat() >= 0 && session.seat() < 2);
      } finally {
        rightPassword.close();
      }
    }
  }

  @Test
  void revanche_unanime_criaPartidaNovaEOsClientesEntramSozinhos() throws Exception {
    // Fase 4.5-4 (ADR-0024): força o fim de uma partida de 2 (direto no MatchService, sem precisar
    // jogar até o fim de verdade), depois exercita requestRematch/confirmRematch pelas duas
    // GrpcClientTransport reais e confirma que ambas recebem RematchStarted e conseguem entrar
    // sozinhas na partida nova.
    try (GrpcHostTransport host = newHost()) {
      String matchId = host.hostMatch("Mesa 1", FREE_FOR_ALL, 2, 20L);

      GrpcClientTransport ta = newClient(host);
      GrpcClientTransport tb = newClient(host);
      Recorder ra = new Recorder();
      Recorder rb = new Recorder();
      ta.addListener(ra);
      tb.addListener(rb);
      try {
        ta.open();
        tb.open();
        GameTransport.Session sa = ta.join(matchId, "Ana");
        GameTransport.Session sb = tb.join(matchId, "Beto");
        ra.await(GameEvent.MatchStarted.class);
        rb.await(GameEvent.MatchStarted.class);

        MatchService match = host.match(matchId);
        match.disconnect(sb.token()).get();
        match.requestElimination(sa.token(), sb.seat()).get(); // só 2 jogadores: elimina e acaba
        assertEquals(MatchService.Phase.FINISHED, match.phase());
        ra.await(GameEvent.MatchEnded.class);
        rb.await(GameEvent.MatchEnded.class);

        ta.requestRematch();
        tb.confirmRematch(); // Beto foi eliminado, mas continua "conectado" — pode votar (4.5-4)

        GameEvent.RematchStarted startedA = ra.await(GameEvent.RematchStarted.class);
        GameEvent.RematchStarted startedB = rb.await(GameEvent.RematchStarted.class);
        assertEquals(startedA.newMatchId(), startedB.newMatchId());
        assertTrue(!startedA.newMatchId().equals(matchId), "tem que ser uma partida nova");

        GameTransport.Session sa2 = ta.join(startedA.newMatchId(), "Ana");
        GameTransport.Session sb2 = tb.join(startedB.newMatchId(), "Beto");
        // Fase 4.5-5 (ADR-0025): cadeira sorteada no modo todos-contra-todos — só confere que
        // cada um pegou uma cadeira válida e distinta, não mais sempre 0/1 na ordem de entrada.
        assertTrue(sa2.seat() == 0 || sa2.seat() == 1);
        assertTrue(sb2.seat() == 0 || sb2.seat() == 1);
        assertTrue(sa2.seat() != sb2.seat());

        long deadline = System.currentTimeMillis() + 2000;
        while (host.match(startedA.newMatchId()).phase() != MatchService.Phase.IN_PROGRESS) {
          if (System.currentTimeMillis() > deadline) {
            fail("a partida da revanche não começou sozinha ao encher");
          }
          Thread.sleep(10);
        }
      } finally {
        ta.close();
        tb.close();
      }
    }
  }

  /** Coletor de eventos recebidos por um cliente, com espera bloqueante por tipo. */
  private static final class Recorder implements GameEventListener {

    private final List<GameEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(GameEvent event) {
      events.add(event);
      synchronized (this) {
        notifyAll();
      }
    }

    synchronized <T extends GameEvent> T await(Class<T> type) throws InterruptedException {
      long deadline = System.currentTimeMillis() + 3000;
      while (true) {
        T hit = latest(type);
        if (hit != null) {
          return hit;
        }
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
          throw new AssertionError("timeout esperando " + type.getSimpleName());
        }
        wait(left);
      }
    }

    <T extends GameEvent> T last(Class<T> type) {
      T hit = latest(type);
      if (hit == null) {
        throw new AssertionError("nenhum " + type.getSimpleName() + " recebido");
      }
      return hit;
    }

    <T extends GameEvent> List<T> all(Class<T> type) {
      return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private <T extends GameEvent> T latest(Class<T> type) {
      T found = null;
      for (GameEvent e : events) {
        if (type.isInstance(e)) {
          found = type.cast(e);
        }
      }
      return found;
    }
  }
}
