package br.com.mss.domino.app;

import br.com.mss.domino.net.config.IdentityTarget;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link IdentitySessionStore} por perfil local de dados, sobre {@link Preferences} — mesmo padrão
 * dos demais stores locais do cliente (no Windows, registro do usuário). Um nó-filho por destino de
 * identidade ({@code host_porta_tls|plain}), para que a sessão de um serviço de identidade nunca
 * seja enviada a outro. Nunca registra o token.
 */
public final class LocalIdentitySessionStore implements IdentitySessionStore {

  private static final String KEY_TOKEN = "sessionToken";
  private static final String KEY_ACCOUNT_ID = "accountId";
  private static final String KEY_EXPIRES_AT = "expiresAt";
  private static final String KEY_STATE = "state";
  private static final String KEY_NICK = "nick";
  private static final String KEY_NICK_ACCOUNT = "nickAccountId";
  private static final String KEY_CONTACT = "maskedContact";
  private static final String KEY_TARGET_HOST = "identityHost";
  private static final String KEY_TARGET_PORT = "identityPort";
  private static final String KEY_TARGET_TLS = "identityTls";
  private static final String KEY_PROVISIONAL_SINCE = "provisionalSince";
  private static final String KEY_PROVISIONAL_ACCOUNT = "provisionalAccountId";

  /** Nome do nó-filho, dentro de cada perfil local, que guarda as sessões MSS. */
  static final String NODE = "mssIdentitySession";

  private static final Pattern NODE_NAME = Pattern.compile("(.+)_(\\d{1,5})_(tls|plain)");

  private final Preferences root;
  private final String nodeName;
  private final IdentityTarget target;

  /**
   * Sessão MSS do perfil local de dados {@code profile} para {@code target}: cada janela aberta tem
   * a sua, e nenhuma é rotacionada por duas janelas ao mesmo tempo (o perfil é travado).
   */
  public LocalIdentitySessionStore(DataProfile profile, IdentityTarget target) {
    this(profile.node(NODE), target);
  }

  LocalIdentitySessionStore(Preferences root, IdentityTarget target) {
    this.root = root;
    this.nodeName = nodeName(target);
    this.target = target;
  }

  private LocalIdentitySessionStore(Preferences root, String nodeName) {
    this.root = root;
    this.nodeName = nodeName;
    this.target = null;
  }

  /** Store de um nó já existente (listagem/remoção em "Gerenciar contas"). */
  static LocalIdentitySessionStore forNode(Preferences root, String nodeName) {
    return new LocalIdentitySessionStore(root, nodeName);
  }

  /**
   * Destino de identidade da sessão guardada: o gravado junto da sessão ou o deduzido do nome do nó
   * ({@code host_porta_tls|plain}); vazio se nenhum.
   */
  Optional<IdentityTarget> storedTarget() {
    if (target != null) {
      return Optional.of(target);
    }
    try {
      if (root.nodeExists(nodeName)) {
        Preferences node = root.node(nodeName);
        String host = node.get(KEY_TARGET_HOST, null);
        int port = node.getInt(KEY_TARGET_PORT, 0);
        if (host != null && !host.isBlank() && port > 0 && port <= 65535) {
          return Optional.of(new IdentityTarget(host, port, node.getBoolean(KEY_TARGET_TLS, true)));
        }
      }
    } catch (BackingStoreException | IllegalArgumentException e) {
      // cai para o nome do nó
    }
    Matcher m = NODE_NAME.matcher(nodeName);
    if (m.matches()) {
      try {
        return Optional.of(
            new IdentityTarget(m.group(1), Integer.parseInt(m.group(2)), m.group(3).equals("tls")));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  String nodeName() {
    return nodeName;
  }

  /** Nome de nó estável e válido para {@link Preferences} (sem '/', até 80 caracteres). */
  static String nodeName(IdentityTarget target) {
    String raw =
        (target.host() + "_" + target.port() + "_" + (target.tls() ? "tls" : "plain"))
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]", "_");
    return raw.length() <= Preferences.MAX_NAME_LENGTH
        ? raw
        : raw.substring(0, 60) + "_" + Integer.toHexString(raw.hashCode());
  }

  @Override
  public synchronized Optional<StoredIdentitySession> load() {
    Preferences node = existing();
    if (node == null) {
      return Optional.empty();
    }
    String token = node.get(KEY_TOKEN, null);
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new StoredIdentitySession(
            token,
            node.get(KEY_ACCOUNT_ID, ""),
            node.getLong(KEY_EXPIRES_AT, 0),
            node.get(KEY_STATE, "PROVISIONAL")));
  }

  @Override
  public synchronized void save(StoredIdentitySession session) {
    Preferences node = root.node(nodeName);
    node.put(KEY_TOKEN, session.sessionToken());
    node.put(KEY_ACCOUNT_ID, session.accountId());
    node.putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds());
    node.put(KEY_STATE, session.state());
    if (target != null) {
      node.put(KEY_TARGET_HOST, target.host());
      node.putInt(KEY_TARGET_PORT, target.port());
      node.putBoolean(KEY_TARGET_TLS, target.tls());
    }
    flush(node);
  }

  @Override
  public synchronized Optional<String> nickFor(String accountId) {
    Preferences node = accountId == null ? null : existing();
    if (node == null) {
      return Optional.empty();
    }
    String nick = node.get(KEY_NICK, null);
    if (nick == null || nick.isBlank() || !accountId.equals(node.get(KEY_NICK_ACCOUNT, ""))) {
      return Optional.empty();
    }
    return Optional.of(nick);
  }

  @Override
  public synchronized Optional<String> maskedContactFor(String accountId) {
    if (nickFor(accountId).isEmpty()) {
      return Optional.empty();
    }
    String contact = root.node(nodeName).get(KEY_CONTACT, null);
    return contact == null || contact.isBlank() ? Optional.empty() : Optional.of(contact);
  }

  @Override
  public synchronized void rememberProfile(String accountId, String nick, String maskedContact) {
    if (accountId == null || nick == null || nick.isBlank()) {
      return;
    }
    Preferences node = root.node(nodeName);
    node.put(KEY_NICK, nick.strip());
    node.put(KEY_NICK_ACCOUNT, accountId);
    if (maskedContact != null) {
      if (maskedContact.isBlank()) {
        node.remove(KEY_CONTACT);
      } else {
        node.put(KEY_CONTACT, maskedContact.strip());
      }
    }
    flush(node);
  }

  @Override
  public synchronized Optional<Instant> provisionalSince(String accountId) {
    Preferences node = accountId == null ? null : existing();
    if (node == null) {
      return Optional.empty();
    }
    long epoch = node.getLong(KEY_PROVISIONAL_SINCE, 0);
    if (epoch <= 0 || !accountId.equals(node.get(KEY_PROVISIONAL_ACCOUNT, ""))) {
      return Optional.empty();
    }
    return Optional.of(Instant.ofEpochSecond(epoch));
  }

  @Override
  public synchronized void rememberProvisionalSince(String accountId, Instant at) {
    if (accountId == null || at == null) {
      return;
    }
    Preferences node = root.node(nodeName);
    node.putLong(KEY_PROVISIONAL_SINCE, at.getEpochSecond());
    node.put(KEY_PROVISIONAL_ACCOUNT, accountId);
    flush(node);
  }

  @Override
  public synchronized void clear() {
    try {
      if (root.nodeExists(nodeName)) {
        root.node(nodeName).removeNode();
        root.flush();
      }
    } catch (BackingStoreException e) {
      throw new IllegalStateException("não foi possível remover a sessão MSS local", e);
    }
  }

  private Preferences existing() {
    try {
      return root.nodeExists(nodeName) ? root.node(nodeName) : null;
    } catch (BackingStoreException e) {
      return null;
    }
  }

  private static void flush(Preferences node) {
    try {
      node.flush();
    } catch (BackingStoreException e) {
      // best-effort: o SO grava sozinho ao sair; não vale abortar a UI por isso
    }
  }
}
