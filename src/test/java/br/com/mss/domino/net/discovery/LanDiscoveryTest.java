package br.com.mss.domino.net.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sockets UDP reais em loopback (sem mock de rede, mesmo espírito dos testes de integração RMI do
 * projeto) — mirando o loopback explicitamente, não o broadcast real, pra ficar determinístico em
 * CI (ver {@code LanServerFinder.find(int, Duration, List)} pacote-visível). Portas bem longe da
 * {@code NetworkConfig.DISCOVERY_PORT} real (17099), pra nunca colidir com um host de
 * desenvolvimento rodando na máquina.
 */
class LanDiscoveryTest {

  private static final int BASE_TEST_PORT = 27099;

  private static InetAddress loopback() throws UnknownHostException {
    return InetAddress.getLoopbackAddress();
  }

  @Test
  void achaUmRespondedorRealNoLoopback() throws IOException {
    ServerDiscoveryResponder responder =
        new ServerDiscoveryResponder(BASE_TEST_PORT, "Servidor de teste", 6000);
    responder.start();
    try {
      List<DiscoveredServer> found =
          LanServerFinder.find(BASE_TEST_PORT, Duration.ofMillis(500), List.of(loopback()));
      assertEquals(
          List.of(new DiscoveredServer("Servidor de teste", loopback().getHostAddress(), 6000)),
          found);
    } finally {
      responder.close();
    }
  }

  @Test
  void semRespondedorNaoEncontraNada() throws UnknownHostException {
    List<DiscoveredServer> found =
        LanServerFinder.find(BASE_TEST_PORT + 1, Duration.ofMillis(200), List.of(loopback()));
    assertTrue(found.isEmpty());
  }

  @Test
  void depoisDeFecharNaoRespondeMais() throws IOException {
    ServerDiscoveryResponder responder =
        new ServerDiscoveryResponder(BASE_TEST_PORT + 2, "X", 7000);
    responder.start();
    responder.close();

    List<DiscoveredServer> found =
        LanServerFinder.find(BASE_TEST_PORT + 2, Duration.ofMillis(200), List.of(loopback()));
    assertTrue(found.isEmpty());
  }

  @Test
  void chamarStartDeNovoEnquantoJaNoArNaoQuebra() throws IOException {
    ServerDiscoveryResponder responder =
        new ServerDiscoveryResponder(BASE_TEST_PORT + 3, "Y", 8000);
    responder.start();
    try {
      responder.start(); // no-op documentado — não deve lançar nem religar noutra porta
      List<DiscoveredServer> found =
          LanServerFinder.find(BASE_TEST_PORT + 3, Duration.ofMillis(300), List.of(loopback()));
      assertEquals(1, found.size());
    } finally {
      responder.close();
    }
  }
}
