package br.com.mss.domino.net.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.GameTransport.Session;
import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.dto.EndDto;
import br.com.mss.domino.net.dto.MoveDto;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** M6-03: opt-in contra o stack local real; nunca aponta para o servidor oficial. */
@EnabledIfEnvironmentVariable(named = "DOMINO_STAGING_CA", matches = ".+")
class GrpcStagingTlsTest {

  private GrpcClientTransport client(String ca) {
    int port = Integer.parseInt(System.getenv().getOrDefault("DOMINO_STAGING_TLS_PORT", "8443"));
    return new GrpcClientTransport("localhost", port, null, true, ca);
  }

  /** Simula perda do canal; close normal envia Leave e invalida a sessão no lobby. */
  private void disconnect(GrpcClientTransport client) throws Exception {
    var token = GrpcClientTransport.class.getDeclaredField("token");
    token.setAccessible(true);
    token.set(client, null);
    client.close();
  }

  @Test
  void caddyExigeCaETransportaLobbyJogadaEReconexao() throws Exception {
    try (var untrusted = client(null)) {
      untrusted.open();
      var failure = assertThrows(TransportException.class, untrusted::listMatches);
      boolean handshake = false;
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        handshake |= cause instanceof SSLHandshakeException;
      }
      assertTrue(handshake, "recusa deve ser de certificado, não de indisponibilidade");
    }

    String ca = System.getenv("DOMINO_STAGING_CA");
    String tokenA;
    String tokenB;
    String matchId;
    try (var a = client(ca)) {
      a.open();
      matchId = a.createMatch("M6-03 staging", GameMode.FREE_FOR_ALL, 2, 120, 120);
      tokenA = a.join(matchId, "Staging A").token();
      disconnect(a);
    }
    try (var a = client(ca);
        var b = client(ca)) {
      a.open();
      b.open();
      Session lobbyA = a.reconnect(tokenA);
      assertEquals("LOBBY", lobbyA.snapshot().phase());
      // FFA começa automaticamente ao preencher a última vaga.
      Session joinedB = b.join(matchId, "Staging B");
      tokenB = joinedB.token();
      assertEquals(matchId, joinedB.snapshot().matchId());
      disconnect(a);
      disconnect(b);
    }
    try (var a = client(ca);
        var b = client(ca)) {
      a.open();
      b.open();
      Session gameA = a.reconnect(tokenA);
      Session gameB = b.reconnect(tokenB);
      assertEquals("IN_PROGRESS", gameA.snapshot().phase());
      assertEquals(gameA.snapshot().currentSeat(), gameB.snapshot().currentSeat());
      var current = gameA.snapshot().currentSeat() == gameA.seat() ? a : b;
      current.sendMove(
          MoveDto.play(
              gameA.snapshot().currentSeat(), gameA.snapshot().openingTile(), EndDto.LEFT));
      disconnect(a);
      disconnect(b);
    }
    try (var resumed = client(ca)) {
      resumed.open();
      Session snapshot = resumed.reconnect(tokenA);
      assertEquals(matchId, snapshot.snapshot().matchId());
      assertEquals("IN_PROGRESS", snapshot.snapshot().phase());
      assertEquals(1, snapshot.snapshot().line().tiles().size());
    }
  }
}
