package br.com.mss.domino.net.grpc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.SessionExpiredException;
import br.com.mss.domino.net.TransportException;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/**
 * BUG-009: um {@code reconnect} com token que o host não conhece mais vira {@link
 * SessionExpiredException} (o token pode ser descartado); queda de rede continua {@link
 * TransportException} comum (o token precisa ser mantido). Contra o host real em memória — o mesmo
 * do servidor embarcado, onde o bug apareceu.
 */
class GrpcClientTransportSessionExpiredTest {

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  void tokenDeUmaSubidaAnteriorDoHostEhSessaoExpirada() throws Exception {
    // 1ª subida do host: entra na m1 e guarda o token
    GrpcHostTransport firstHost = new GrpcHostTransport(freePort());
    firstHost.start();
    String oldToken;
    GrpcClientTransport client = new GrpcClientTransport("127.0.0.1", firstHost.port());
    try {
      client.open();
      String matchId = client.createMatch("Mesa", GameMode.FREE_FOR_ALL, 2, 30, 120);
      oldToken = client.join(matchId, "Ana").token();
    } finally {
      client.close();
      firstHost.close();
    }

    // 2ª subida (em memória, ids recomeçam): o token velho não existe mais
    GrpcHostTransport secondHost = new GrpcHostTransport(freePort());
    secondHost.start();
    GrpcClientTransport again = new GrpcClientTransport("127.0.0.1", secondHost.port());
    try {
      again.open();
      again.createMatch("Outra mesa", GameMode.FREE_FOR_ALL, 2, 30, 120); // também m1

      assertThrows(SessionExpiredException.class, () -> again.reconnect(oldToken));
    } finally {
      again.close();
      secondHost.close();
    }
  }

  @Test
  void hostForaDoArNaoEhSessaoExpirada() throws Exception {
    GrpcClientTransport client = new GrpcClientTransport("127.0.0.1", freePort());
    try {
      client.open();

      TransportException e =
          assertThrows(TransportException.class, () -> client.reconnect("qualquer-token"));
      assertFalse(e instanceof SessionExpiredException, e.toString());
    } finally {
      client.close();
    }
  }
}
