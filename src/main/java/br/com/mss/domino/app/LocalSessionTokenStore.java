package br.com.mss.domino.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Tokens de sessão por dispositivo, sobre {@link Preferences} — mesmo padrão de {@link
 * LocalProfileStore}. Só se lê e grava por {@link #forServer}, que devolve o {@link
 * SessionTokenStore} de um servidor.
 *
 * <p>Layout: um nó por {@link PlayerId perfil} ({@link #useProfile}), dentro dele um nó por
 * servidor ({@code host_porta}) e dentro deste um nó por {@code matchId + "|" + seat}, chave {@code
 * token}. Tokens gravados antes do BUG-009 (sem o nível do servidor) ficam órfãos e são ignorados.
 * Sem expiração: partidas são efêmeras, entradas órfãs não fazem mal, e um token que o host recusa
 * como desconhecido é removido ({@link SessionTokenStore#remove}).
 *
 * <p><b>Quem enxerga o quê:</b> {@link Preferences} de usuário é por conta do SO — contas
 * diferentes do Windows nunca compartilham nada. Na mesma conta, dois clientes compartilhariam o
 * armazenamento (incidente de 18/09/2026, BUG-006); o nível do perfil isola cada jogador local —
 * {@link br.com.mss.domino.ui.MainWindow} chama {@link #useProfile} sempre que o perfil ativo muda.
 *
 * <p><b>Por que por servidor (BUG-009):</b> ids de partida ({@code m1}, {@code m2}…) se repetem
 * entre servidores e recomeçam a cada subida do servidor embarcado (em memória). Sem o servidor na
 * chave, o token de uma {@code m1} antiga casava com a {@code m1} nova, "Entrar" tentava reconectar
 * com ele e o host recusava — e o token ainda podia ir para um servidor que não o emitiu.
 */
public final class LocalSessionTokenStore {

  private static final String KEY_TOKEN = "token";
  private static final String DEFAULT_SCOPE = "sem-perfil";

  private final Preferences root;
  private volatile String scope = DEFAULT_SCOPE;

  public LocalSessionTokenStore() {
    this(Preferences.userNodeForPackage(LocalSessionTokenStore.class).node("session-tokens"));
  }

  LocalSessionTokenStore(Preferences root) {
    this.root = root;
  }

  /** Escopa toda leitura/gravação seguinte ao perfil local ativo (ADR-0018). */
  public void useProfile(PlayerId profile) {
    scope = profile.value();
  }

  /**
   * Os tokens do perfil ativo <b>no momento de cada chamada</b> para o servidor {@code host:port}.
   */
  public SessionTokenStore forServer(String host, int port) {
    String server = serverNodeName(host, port);
    return new SessionTokenStore() {
      @Override
      public Optional<String> find(String matchId, int seat) {
        return LocalSessionTokenStore.this.find(server, matchId, seat);
      }

      @Override
      public void save(String matchId, int seat, String token) {
        LocalSessionTokenStore.this.save(server, matchId, seat, token);
      }

      @Override
      public void remove(String matchId, int seat) {
        LocalSessionTokenStore.this.remove(server, matchId, seat);
      }
    };
  }

  private synchronized Optional<String> find(String server, String matchId, int seat) {
    try {
      String nodeName = nodeName(server, matchId, seat);
      if (!root.nodeExists(nodeName)) {
        return Optional.empty();
      }
      String token = root.node(nodeName).get(KEY_TOKEN, null);
      return token == null || token.isBlank() ? Optional.empty() : Optional.of(token);
    } catch (BackingStoreException e) {
      return Optional.empty();
    }
  }

  private synchronized void save(String server, String matchId, int seat, String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    root.node(nodeName(server, matchId, seat)).put(KEY_TOKEN, token);
    flush();
  }

  private synchronized void remove(String server, String matchId, int seat) {
    try {
      String nodeName = nodeName(server, matchId, seat);
      if (root.nodeExists(nodeName)) {
        root.node(nodeName).removeNode();
        flush();
      }
    } catch (BackingStoreException e) {
      // best-effort: um token que ficou para trás só gera uma nova tentativa recusada
    }
  }

  private void flush() {
    try {
      root.flush();
    } catch (BackingStoreException e) {
      // best-effort: o SO grava sozinho ao sair; não vale abortar a UI por isso
    }
  }

  private String nodeName(String server, String matchId, int seat) {
    return scope + "/" + server + "/" + matchId + "|" + seat;
  }

  /**
   * {@code host_porta} em minúsculas; nome de nó do {@link Preferences} não aceita {@code /} e tem
   * no máximo {@value Preferences#MAX_NAME_LENGTH} caracteres, então um host fora disso vira hash.
   */
  static String serverNodeName(String host, int port) {
    String plain = host.strip().toLowerCase(Locale.ROOT) + "_" + port;
    if (plain.length() <= Preferences.MAX_NAME_LENGTH && plain.indexOf('/') < 0) {
      return plain;
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
      return "h-" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível", e);
    }
  }
}
