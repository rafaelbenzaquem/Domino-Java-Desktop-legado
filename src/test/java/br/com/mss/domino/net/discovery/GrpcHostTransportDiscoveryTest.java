package br.com.mss.domino.net.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.NetworkConfig;
import br.com.mss.domino.net.grpc.GrpcHostTransport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Prova a ponta que falta entre {@code GrpcTransportTest} (gRPC puro) e {@code
 * DiscoveryProtocolTest}/{@code LanDiscoveryTest} (protocolo/broadcast isolados): {@code
 * GrpcHostTransport.start(true)} liga mesmo o {@link ServerDiscoveryResponder} (ADR-0021), na porta
 * fixa {@link NetworkConfig#DISCOVERY_PORT} de verdade — por isso fica sozinho neste teste (nenhum
 * outro cria host com descoberta ligada, para não disputar a porta). Substitui o antigo {@code
 * RmiHostTransportDiscoveryTest} (Fase 6a-4) — a descoberta em si não mudou, só o transporte que
 * ela anuncia.
 */
class GrpcHostTransportDiscoveryTest {

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  void startComDescobertaLigada_respondeNaPortaFixaDaAdr0021() throws Exception {
    int port = freePort();
    try (GrpcHostTransport host = new GrpcHostTransport(port)) {
      host.start(true);

      List<DiscoveredServer> found =
          LanServerFinder.find(
              NetworkConfig.DISCOVERY_PORT,
              Duration.ofMillis(500),
              List.of(InetAddress.getLoopbackAddress()));

      assertEquals(1, found.size());
      assertEquals(host.port(), found.get(0).port());
    }
  }

  @Test
  void startSemDescoberta_naoRespondeNaPortaFixa() throws Exception {
    try (GrpcHostTransport host = new GrpcHostTransport(freePort())) {
      host.start(false);

      List<DiscoveredServer> found =
          LanServerFinder.find(
              NetworkConfig.DISCOVERY_PORT,
              Duration.ofMillis(200),
              List.of(InetAddress.getLoopbackAddress()));

      assertTrue(found.isEmpty());
    }
  }
}
