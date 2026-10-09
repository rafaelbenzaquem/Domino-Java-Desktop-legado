package br.com.mss.domino;

import br.com.mss.domino.app.DataProfile;
import br.com.mss.domino.app.DataProfileException;
import br.com.mss.domino.net.NetworkConfig;
import br.com.mss.domino.net.config.IdentityTarget;
import br.com.mss.domino.net.config.ServerPreset;
import br.com.mss.domino.net.grpc.GrpcHostTransport;
import br.com.mss.domino.ui.MainWindow;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point do cliente. Abre a {@link MainWindow} (conectar → lista → lobby → jogo). O servidor é
 * o {@link HostMain} — ou, com {@code --embedded-server}, este mesmo processo (ADR-0020), sem
 * precisar de um segundo processo/terminal para hospedar uma partida casual em LAN.
 *
 * <pre>./mvnw -q exec:java                                    (cliente puro)
 * java -jar target/domino.jar --embedded-server             (hospeda em localhost:1099)
 * java -jar target/domino.jar --embedded-server --port=1100 (outra porta)
 * java -jar target/domino.jar --embedded-server --no-lan-discovery (sem broadcast UDP, ADR-0021)
 * java -jar target/domino.jar --server=192.168.0.5:1099     (pré-seleciona outro servidor, ADR-0022)
 * java -jar target/domino.jar --server=localhost:1099 --identity=localhost:9100 --identity-plaintext
 *                                                           (servidor com conta MSS, M1)
 * java -jar target/domino.jar --perfil=teste                (perfil local de dados desta janela, M1)
 * </pre>
 *
 * <p>{@code --identity=host:porta} (M1) só vale junto de {@code --server=} e usa TLS; {@code
 * --identity-plaintext} usa texto puro, aceito só para localhost/loopback. {@code --perfil=nome}
 * abre o perfil local {@code nome} (sessão MSS, tokens de assento e perfis de jogador próprios);
 * sem ele, a 1ª janela usa o perfil padrão e as seguintes o próximo livre ({@link DataProfile}).
 * Uso inválido dessas opções ou perfil já aberto em outra janela encerram com mensagem clara.
 */
public final class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  private static final String FLAG_EMBEDDED_SERVER = "--embedded-server";
  static final String FLAG_NO_LAN_DISCOVERY = "--no-lan-discovery";
  private static final String OPTION_PORT = "--port";
  private static final String OPTION_SERVER = "--server";
  static final String OPTION_IDENTITY = "--identity";
  static final String FLAG_IDENTITY_PLAINTEXT = "--identity-plaintext";
  static final String OPTION_PERFIL = "--perfil";

  private Main() {}

  public static void main(String[] args) {
    warnUnknownArgs(args);
    boolean embeddedServer = hasFlag(args, FLAG_EMBEDDED_SERVER);
    boolean lanDiscovery = !hasFlag(args, FLAG_NO_LAN_DISCOVERY);
    int port = intOption(args, OPTION_PORT, NetworkConfig.DEFAULT_PORT);
    NetworkConfig config = new NetworkConfig("localhost", port);
    ServerPreset cliServer;
    DataProfile dataProfile;
    try {
      cliServer =
          withIdentityOption(
              parseServerOption(stringOption(args, OPTION_SERVER)),
              stringOption(args, OPTION_IDENTITY),
              hasFlag(args, FLAG_IDENTITY_PLAINTEXT));
      String perfil = stringOption(args, OPTION_PERFIL);
      dataProfile =
          DataProfile.acquire(
              DataProfile.defaultDataDir(), perfil == null ? null : DataProfile.normalize(perfil));
    } catch (IllegalArgumentException | DataProfileException e) {
      exitWithMessage(e.getMessage());
      return;
    }
    LOG.info("perfil local de dados: {}", dataProfile.displayName());
    List<String> launchArgs = List.of(args);

    GrpcHostTransport embeddedHost =
        embeddedServer ? startEmbeddedServer(config, lanDiscovery) : null;
    NetworkConfig autoConnect = embeddedHost != null ? config : null;

    SwingUtilities.invokeLater(
        () -> {
          try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
          } catch (Exception ignored) {
            // fica com o Look & Feel padrão
          }
          new MainWindow(autoConnect, embeddedHost, cliServer, dataProfile, launchArgs)
              .setVisible(true);
        });
  }

  /** Erro de linha de comando ou perfil em uso: avisa (console e, se houver tela, janela) e sai. */
  private static void exitWithMessage(String message) {
    LOG.error(message);
    System.err.println(message);
    if (!GraphicsEnvironment.isHeadless()) {
      JOptionPane.showMessageDialog(null, message, "Dominó", JOptionPane.ERROR_MESSAGE);
    }
    System.exit(1);
  }

  /**
   * Acrescenta a identidade MSS de {@code --identity=} ao servidor de {@code --server=} (M1).
   *
   * @throws IllegalArgumentException {@code --identity=} sem {@code --server=}, destino inválido,
   *     ou texto puro fora de localhost.
   */
  static ServerPreset withIdentityOption(
      ServerPreset cliServer, String identitySpec, boolean plaintext) {
    if (identitySpec == null) {
      if (plaintext) {
        throw new IllegalArgumentException(
            FLAG_IDENTITY_PLAINTEXT + " exige " + OPTION_IDENTITY + "=host:porta.");
      }
      return cliServer;
    }
    if (cliServer == null) {
      throw new IllegalArgumentException(
          OPTION_IDENTITY
              + "= exige "
              + OPTION_SERVER
              + "=host:porta (a identidade vale para o servidor indicado na linha de comando).");
    }
    IdentityTarget identity = IdentityTarget.parse(identitySpec, !plaintext);
    if (!identity.plaintextAllowed()) {
      throw new IllegalArgumentException(
          FLAG_IDENTITY_PLAINTEXT
              + " só é aceito para localhost; use TLS para \""
              + identity.authority()
              + "\".");
    }
    return new ServerPreset(
        "linha de comando",
        cliServer.host(),
        cliServer.port(),
        cliServer.isDefault(),
        cliServer.tls(),
        false,
        identity);
  }

  /**
   * Sobe um {@link GrpcHostTransport} neste processo, no mesmo bootstrap do {@link HostMain}
   * (ADR-0020). Devolve {@code null} se o servidor embarcado não subiu (a janela abre normalmente,
   * como cliente puro). O {@link Runtime#addShutdownHook} é uma rede de segurança para Ctrl+C/kill
   * — o caminho normal de encerramento é {@link MainWindow} fechando o host ao fechar a janela
   * (D6).
   */
  private static GrpcHostTransport startEmbeddedServer(NetworkConfig config, boolean lanDiscovery) {
    try {
      GrpcHostTransport host = new GrpcHostTransport(config.port());
      host.start(lanDiscovery);
      Runtime.getRuntime().addShutdownHook(new Thread(host::close));
      LOG.info("servidor embarcado no ar em localhost:{}", config.port());
      return host;
    } catch (IOException e) {
      LOG.error("falha ao subir o servidor embarcado — abrindo como cliente puro", e);
      return null;
    }
  }

  /**
   * Avisa (não falha) sobre argumentos que não batem com nenhuma flag/opção conhecida — evita o
   * erro de digitação virar um "cliente puro" silencioso quando a intenção era hospedar (achado na
   * fase 3c-2: {@code --embedded-serve} ou {@code --port 1010} sem {@code =} eram ignorados sem
   * aviso nenhum).
   */
  static void warnUnknownArgs(String[] args) {
    for (String arg : args) {
      if (!arg.equals(FLAG_EMBEDDED_SERVER)
          && !arg.equals(FLAG_NO_LAN_DISCOVERY)
          && !arg.equals(FLAG_IDENTITY_PLAINTEXT)
          && !arg.startsWith(OPTION_PORT + "=")
          && !arg.startsWith(OPTION_SERVER + "=")
          && !arg.startsWith(OPTION_IDENTITY + "=")
          && !arg.startsWith(OPTION_PERFIL + "=")) {
        LOG.warn("argumento desconhecido, ignorado: {}", arg);
      }
    }
  }

  static boolean hasFlag(String[] args, String flag) {
    for (String arg : args) {
      if (arg.equals(flag)) {
        return true;
      }
    }
    return false;
  }

  /** {@code --nome=valor}; devolve {@code defaultValue} se ausente ou não for um número. */
  static int intOption(String[] args, String name, int defaultValue) {
    String prefix = name + "=";
    for (String arg : args) {
      if (arg.startsWith(prefix)) {
        try {
          return Integer.parseInt(arg.substring(prefix.length()));
        } catch (NumberFormatException e) {
          LOG.warn("valor inválido para {}: {}", name, arg);
        }
      }
    }
    return defaultValue;
  }

  /** {@code --nome=valor}; devolve {@code null} se ausente. */
  static String stringOption(String[] args, String name) {
    String prefix = name + "=";
    for (String arg : args) {
      if (arg.startsWith(prefix)) {
        return arg.substring(prefix.length());
      }
    }
    return null;
  }

  /**
   * {@code host} ou {@code host:porta} (ADR-0022) — {@code null} se {@code spec} for {@code null}
   * ou em branco (nenhum {@code --server=} na linha de comando). Formato inválido cai para a porta
   * padrão em vez de recusar a subir, já que este é só o valor sugerido inicial da {@code
   * ConnectCard}, não uma conexão que já aconteceu.
   */
  static ServerPreset parseServerOption(String spec) {
    if (spec == null || spec.isBlank()) {
      return null;
    }
    String host = spec;
    int port = NetworkConfig.DEFAULT_PORT;
    int colon = spec.indexOf(':');
    if (colon > 0) {
      host = spec.substring(0, colon).trim();
      try {
        port = Integer.parseInt(spec.substring(colon + 1).trim());
      } catch (NumberFormatException e) {
        LOG.warn("porta inválida em --server={}, usando {}", spec, port);
      }
    }
    return new ServerPreset("linha de comando", host, port, true);
  }
}
