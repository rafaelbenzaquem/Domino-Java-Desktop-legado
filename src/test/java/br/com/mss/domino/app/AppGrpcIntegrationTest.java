package br.com.mss.domino.app;

import static br.com.mss.domino.domain.GameMode.FREE_FOR_ALL;
import static br.com.mss.domino.domain.GameMode.PARTNERSHIP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import br.com.mss.domino.domain.End;
import br.com.mss.domino.domain.Line;
import br.com.mss.domino.net.DtoMapper;
import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.dto.EndDto;
import br.com.mss.domino.net.dto.TileDto;
import br.com.mss.domino.net.grpc.GrpcClientTransport;
import br.com.mss.domino.net.grpc.GrpcHostTransport;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Pilha inteira do cliente: {@link MatchController} + {@link GrpcClientTransport} contra um {@link
 * GrpcHostTransport} de verdade. Os dois "jogadores" escolhem as jogadas <b>só a partir da própria
 * {@link ClientGameView}</b> (nunca do estado do host), então uma partida completa aqui prova que a
 * projeção do cliente é fiel — o mesmo que o antigo {@code AppRmiIntegrationTest} provava sobre
 * RMI.
 */
class AppGrpcIntegrationTest {

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
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

  @Test
  void twoControllers_playAFullGameOffTheirOwnProjection() throws Exception {
    try (GrpcHostTransport host = newHost()) {
      String matchId = host.hostMatch("Mesa", FREE_FOR_ALL, 2, 20L);

      GrpcClientTransport ta = newClient(host);
      GrpcClientTransport tb = newClient(host);
      MatchController ca = new MatchController(ta, new ClientEventBus());
      MatchController cb = new MatchController(tb, new ClientEventBus());
      try {
        ca.open();
        cb.open();
        ca.join(matchId, "Ana");
        cb.join(matchId, "Beto");

        awaitTrue(() -> inProgress(ca) && inProgress(cb), 3000);

        int guard = 0;
        while (!finished(ca)) {
          if (++guard > 600) {
            fail("partida não terminou");
          }
          flushEdt();
          MatchController mover = whoPlays(ca, cb);
          if (mover == null) {
            Thread.sleep(15); // entre turnos / servidor comprando do dorme
            continue;
          }
          ClientGameView view = mover.view();
          Optional<TileDto> pick = view.myHand().tiles().stream().filter(view::canPlay).findFirst();
          if (pick.isEmpty()) {
            Thread.sleep(15);
            continue;
          }
          int before = mover.view().myHand().tiles().size();
          try {
            mover.play(pick.get(), endFor(view, pick.get()));
          } catch (TransportException stale) {
            // a projeção de "mover" ficou obsoleta entre ler a vista e mandar a jogada (o stream
            // de eventos dele ainda não processou algo que o servidor já aplicou) — não é bug,
            // é o preço normal de cada cliente ter seu próprio stream independente; reavalia.
            continue;
          }
          MatchController m = mover;
          awaitTrue(() -> m.view().myHand().tiles().size() < before || finished(m), 3000);
        }

        flushEdt();
        assertTrue(finished(cb), "os dois clientes veem o fim");
        assertEquals(ca.view().outcome(), cb.view().outcome());
      } finally {
        ca.close();
        cb.close();
      }
    }
  }

  @Test
  void partnershipLobby_thenA2v2GamePlaysThroughTheControllers() throws Exception {
    try (GrpcHostTransport host = newHost()) {
      String matchId = host.hostMatch("Duplas", PARTNERSHIP, 4, 42L);

      MatchController[] cs = new MatchController[4];
      GrpcClientTransport[] ts = new GrpcClientTransport[4];
      try {
        for (int i = 0; i < 4; i++) {
          ts[i] = newClient(host);
          cs[i] = new MatchController(ts[i], new ClientEventBus());
          cs[i].open();
          cs[i].join(matchId, "p" + i);
        }
        for (int i = 0; i < 4; i++) {
          cs[i].takeChair(i);
          cs[i].setReady(true);
        }
        flushEdt();
        cs[0].startMatch();

        awaitTrue(() -> inProgress(cs[0]) && inProgress(cs[3]), 3000);

        int guard = 0;
        while (!anyFinished(cs)) {
          if (++guard > 800) {
            fail("partida de duplas não terminou");
          }
          flushEdt();
          MatchController mover = null;
          for (MatchController c : cs) {
            if (c.view() != null && c.view().isMyTurn()) {
              mover = c;
              break;
            }
          }
          if (mover == null) {
            Thread.sleep(15);
            continue;
          }
          ClientGameView view = mover.view();
          Optional<TileDto> pick = view.myHand().tiles().stream().filter(view::canPlay).findFirst();
          MatchController m = mover;
          int handBefore = view.myHand().tiles().size();
          try {
            if (pick.isEmpty()) {
              mover.pass(); // sem jogada -> passe voluntário
              int seatBefore = view.currentSeat();
              awaitTrue(() -> m.view().currentSeat() != seatBefore || finished(m), 3000);
            } else {
              mover.play(pick.get(), endFor(view, pick.get()));
              awaitTrue(() -> m.view().myHand().tiles().size() < handBefore || finished(m), 3000);
            }
          } catch (TransportException stale) {
            // mesma corrida benigna do outro teste: a vista de "mover" ficou obsoleta entre lê-la
            // e agir — cada MatchController tem seu próprio stream, sem sincronia entre eles.
          }
        }

        // um cliente vendo o fim não significa que os outros três já processaram o MatchEnded
        // deles (streams independentes) — espera todo mundo antes de comparar os desfechos.
        awaitTrue(() -> allFinished(cs), 3000);
        flushEdt();
        var outcome = cs[0].view().outcome();
        assertNotNull(outcome);
        assertTrue(outcome.winningTeam() != null || outcome.draw(), "termina por dupla ou empate");
        for (MatchController c : cs) {
          assertTrue(finished(c));
          assertEquals(outcome, c.view().outcome());
        }
      } finally {
        for (MatchController c : cs) {
          if (c != null) {
            c.close();
          }
        }
      }
    }
  }

  private static MatchController whoPlays(MatchController a, MatchController b) {
    if (a.view() != null && a.view().isMyTurn()) {
      return a;
    }
    if (b.view() != null && b.view().isMyTurn()) {
      return b;
    }
    return null;
  }

  private static EndDto endFor(ClientGameView view, TileDto tile) {
    Line line = DtoMapper.line(view.line());
    if (line.isEmpty() || line.fits(DtoMapper.tile(tile), End.LEFT)) {
      return EndDto.LEFT;
    }
    return EndDto.RIGHT;
  }

  private static boolean inProgress(MatchController c) {
    return c.view() != null && c.view().phase() == ClientGameView.Phase.IN_PROGRESS;
  }

  private static boolean finished(MatchController c) {
    return c.view() != null && c.view().isFinished();
  }

  /**
   * Cada {@link MatchController} tem seu próprio stream de eventos — um ver o fim não significa que
   * os outros já processaram o {@code MatchEnded} deles. {@code anyFinished} decide quando parar de
   * jogar (a partida acabou pra valer assim que qualquer um sabe); {@code allFinished} espera todo
   * mundo antes de comparar desfechos entre clientes.
   */
  private static boolean anyFinished(MatchController[] cs) {
    for (MatchController c : cs) {
      if (finished(c)) {
        return true;
      }
    }
    return false;
  }

  private static boolean allFinished(MatchController[] cs) {
    for (MatchController c : cs) {
      if (!finished(c)) {
        return false;
      }
    }
    return true;
  }

  private static void flushEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static void awaitTrue(BooleanSupplier condition, long millis) throws Exception {
    long deadline = System.currentTimeMillis() + millis;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        fail("condição não satisfeita a tempo");
      }
      Thread.sleep(10);
    }
  }
}
