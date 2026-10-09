package br.com.mss.domino.net.discovery;

import br.com.mss.domino.net.NetworkConfig;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Procura servidores na rede local (ADR-0021): manda um pedido em broadcast e recolhe as respostas
 * até o timeout. É "melhor esforço" — não atravessa roteador de propósito (é descoberta de rede
 * local, não um serviço de matchmaking) e uma rede que bloqueia broadcast UDP simplesmente não
 * encontra nada, sem erro visível (quem chama sempre tem o "Oficial"/`servers.json` — Fase 3c-4 —
 * como alternativa).
 */
public final class LanServerFinder {

  private static final Logger LOG = LoggerFactory.getLogger(LanServerFinder.class);

  private LanServerFinder() {}

  public static List<DiscoveredServer> find(Duration timeout) {
    return find(NetworkConfig.DISCOVERY_PORT, timeout, broadcastAddresses());
  }

  /**
   * Pacote-visível para teste: mira endereços específicos (ex.: loopback) em vez do broadcast real.
   */
  static List<DiscoveredServer> find(
      int discoveryPort, Duration timeout, List<InetAddress> targets) {
    Map<String, DiscoveredServer> found = new LinkedHashMap<>();
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setBroadcast(true);
      byte[] request = DiscoveryProtocol.requestBytes();
      for (InetAddress target : targets) {
        try {
          socket.send(new DatagramPacket(request, request.length, target, discoveryPort));
        } catch (IOException e) {
          LOG.debug("falha ao mandar descoberta para {}: {}", target, e.getMessage());
        }
      }
      collectResponses(socket, timeout, found);
    } catch (IOException e) {
      LOG.debug("descoberta de LAN falhou: {}", e.getMessage());
    }
    return List.copyOf(found.values());
  }

  private static void collectResponses(
      DatagramSocket socket, Duration timeout, Map<String, DiscoveredServer> found) {
    byte[] buffer = new byte[DiscoveryProtocol.MAX_PACKET_BYTES];
    long deadline = System.currentTimeMillis() + Math.max(0, timeout.toMillis());
    while (true) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        return;
      }
      try {
        socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        DiscoveryProtocol.decodeResponse(
                packet.getAddress().getHostAddress(), packet.getData(), packet.getLength())
            .ifPresent(server -> found.putIfAbsent(server.host() + ":" + server.port(), server));
      } catch (SocketTimeoutException e) {
        return;
      } catch (IOException e) {
        return;
      }
    }
  }

  /** Broadcast global + o de cada interface de rede ativa (mais confiável em redes reais). */
  private static List<InetAddress> broadcastAddresses() {
    List<InetAddress> addresses = new ArrayList<>();
    try {
      addresses.add(InetAddress.getByName("255.255.255.255"));
    } catch (UnknownHostException e) {
      // não deveria acontecer com um literal IPv4 válido
    }
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface networkInterface = interfaces.nextElement();
        if (networkInterface.isLoopback() || !networkInterface.isUp()) {
          continue;
        }
        for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
          InetAddress broadcast = interfaceAddress.getBroadcast();
          if (broadcast != null) {
            addresses.add(broadcast);
          }
        }
      }
    } catch (SocketException e) {
      LOG.debug("não foi possível listar interfaces de rede: {}", e.getMessage());
    }
    return addresses;
  }
}
