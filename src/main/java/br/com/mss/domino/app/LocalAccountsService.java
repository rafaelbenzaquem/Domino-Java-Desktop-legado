package br.com.mss.domino.app;

import br.com.mss.domino.app.IdentitySessionStore.StoredIdentitySession;
import br.com.mss.domino.net.config.IdentityTarget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * "Gerenciar contas" (M1): enumera e remove <b>deste computador</b> as contas MSS e os perfis
 * locais de dados (um por janela) guardados localmente. Sem Swing e sem rede: nunca exclui conta no
 * servidor; o encerramento remoto opcional de uma sessão MSS fica com quem chama ({@link
 * RemoteSignOut}). Versão reduzida do serviço do TchowStrick-Java-Desktop-Legado: o Dominó não tem
 * conta oficial antiga nem carteira, e perfis de jogador continuam em "Trocar perfil…".
 *
 * <p>Perfis locais em uso por outra janela aberta (trava de {@link DataProfile}) não podem ter nada
 * removido; os livres são travados durante a remoção.
 */
public final class LocalAccountsService {

  /** Tipo de registro local. */
  public enum Kind {
    MSS("Conta MSS"),
    DATA_PROFILE("Perfil local (janela)");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** Quem está usando o perfil local onde o registro está guardado. */
  public enum Usage {
    THIS_WINDOW("esta janela"),
    OTHER_WINDOW("outra janela aberta"),
    FREE("não");

    private final String label;

    Usage(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * Uma linha do painel. {@code key} identifica o registro dentro do perfil local (nó da sessão MSS
   * ou nome do perfil); nunca contém token.
   */
  public record Entry(
      Kind kind,
      String dataProfile,
      String key,
      String name,
      String destination,
      String account,
      String state,
      Usage usage) {

    /** Perfil local exibido ("padrão" para o padrão). */
    public String dataProfileLabel() {
      return DataProfile.displayName(dataProfile);
    }
  }

  /** Encerramento remoto opcional de uma sessão MSS (só "este dispositivo"). */
  @FunctionalInterface
  public interface RemoteSignOut {
    void signOut(IdentityTarget target, IdentitySessionStore store, String deviceId);
  }

  /** Sufixo do estado MSS: a lista lê só os dados locais, sem consultar a identidade. */
  static final String LAST_KNOWN = " (último estado conhecido)";

  private final Path dataDir;
  private final DataProfile current;
  private final Preferences appRoot;
  private final Clock clock;

  /** Perfis locais em {@code dataDir}; {@code current} é o da janela que abriu o painel. */
  public LocalAccountsService(Path dataDir, DataProfile current) {
    this(dataDir, current, Preferences.userNodeForPackage(DataProfile.class), Clock.systemUTC());
  }

  LocalAccountsService(Path dataDir, DataProfile current, Preferences appRoot, Clock clock) {
    this.dataDir = dataDir;
    this.current = current;
    this.appRoot = appRoot;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ listar

  /** Todos os registros locais, agrupados por perfil local (padrão primeiro). */
  public List<Entry> list() {
    List<Entry> entries = new ArrayList<>();
    for (String name : DataProfile.knownNames(dataDir, appRoot)) {
      Usage usage = usage(name);
      entries.add(
          new Entry(
              Kind.DATA_PROFILE,
              name,
              name,
              DataProfile.displayName(name),
              "—",
              "—",
              DataProfile.DEFAULT_NAME.equals(name) ? "padrão (dados anteriores aos perfis)" : "—",
              usage));
      Preferences root = existingRoot(name);
      if (root != null) {
        mssEntries(name, root, usage, entries);
      }
    }
    return List.copyOf(entries);
  }

  /** Nó do perfil local sem criá-lo; {@code null} se ele ainda não tem dados. */
  private Preferences existingRoot(String name) {
    if (DataProfile.DEFAULT_NAME.equals(name)) {
      return appRoot;
    }
    Preferences profiles = child(appRoot, DataProfile.PROFILES_NODE);
    return profiles == null ? null : child(profiles, name);
  }

  private Usage usage(String name) {
    if (current != null && current.name().equals(name)) {
      return Usage.THIS_WINDOW;
    }
    return DataProfile.lockedElsewhere(dataDir, name) ? Usage.OTHER_WINDOW : Usage.FREE;
  }

  private void mssEntries(String profile, Preferences root, Usage usage, List<Entry> out) {
    Preferences sessions = child(root, LocalIdentitySessionStore.NODE);
    if (sessions == null) {
      return;
    }
    for (String node : children(sessions)) {
      LocalIdentitySessionStore store = LocalIdentitySessionStore.forNode(sessions, node);
      Optional<StoredIdentitySession> session = store.load();
      if (session.isEmpty()) {
        continue;
      }
      String accountId = session.get().accountId();
      String destination =
          store
              .storedTarget()
              .map(t -> t.authority() + (t.tls() ? " (TLS)" : " (sem TLS)"))
              .orElse(node);
      out.add(
          new Entry(
              Kind.MSS,
              profile,
              node,
              store.nickFor(accountId).orElse("—"),
              destination,
              store.maskedContactFor(accountId).orElse(shortId(accountId)),
              mssState(session.get()),
              usage));
    }
  }

  private String mssState(StoredIdentitySession session) {
    String state =
        switch (session.state()) {
          case "ACTIVE" -> "ativa";
          case "RESTRICTED" -> "restrita";
          case "PROVISIONAL" -> "provisória";
          default -> session.state().toLowerCase(Locale.ROOT);
        };
    return session.expiresAtEpochSeconds() <= clock.instant().getEpochSecond()
        ? "expirada"
        : state + LAST_KNOWN;
  }

  // ------------------------------------------------------------------ remover

  /** Texto do diálogo de confirmação: exatamente o que será apagado deste computador. */
  public String removalDescription(Entry entry) {
    String where = " do perfil local \"" + entry.dataProfileLabel() + "\"";
    return switch (entry.kind()) {
      case MSS ->
          "Apaga deste computador a sessão da conta MSS "
              + label(entry)
              + " (identidade "
              + entry.destination()
              + ")"
              + where
              + ", com o nick e o contato lembrados para exibição. A conta continua existindo na"
              + " identidade MSS; para jogar com ela de novo, entre com e-mail e código.";
      case DATA_PROFILE ->
          "Apaga deste computador o perfil local \""
              + entry.dataProfileLabel()
              + "\" inteiro: sessões MSS, tokens de assento das partidas e perfis de jogador"
              + " guardados nele. Nenhuma conta é excluída no servidor.";
    };
  }

  /** Motivo pelo qual {@code entry} não pode ser removido agora; vazio se pode. */
  public Optional<String> removalBlocker(Entry entry) {
    if (entry.kind() == Kind.DATA_PROFILE && DataProfile.DEFAULT_NAME.equals(entry.dataProfile())) {
      return Optional.of(
          "O perfil local padrão não pode ser removido; remova as contas dentro dele.");
    }
    if (entry.kind() == Kind.DATA_PROFILE && usage(entry.dataProfile()) == Usage.THIS_WINDOW) {
      return Optional.of(
          "Este é o perfil local desta janela. Para removê-lo, abra o painel em outra janela"
              + " depois de fechar esta.");
    }
    if (usage(entry.dataProfile()) == Usage.OTHER_WINDOW) {
      return Optional.of(
          "O perfil local \""
              + entry.dataProfileLabel()
              + "\" está aberto em outra janela do Dominó. Feche-a antes de remover.");
    }
    return Optional.empty();
  }

  /**
   * Remove {@code entry} deste computador. Para conta MSS, {@code remote} (opcional) encerra antes
   * a sessão no servidor só neste dispositivo; uma falha remota não impede a remoção local e é
   * devolvida como aviso.
   *
   * @return aviso para o jogador (vazio se tudo correu bem).
   * @throws DataProfileException perfil em uso por outra janela, ou o padrão inteiro.
   */
  public Optional<String> remove(Entry entry, RemoteSignOut remote) {
    removalBlocker(entry)
        .ifPresent(
            reason -> {
              throw new DataProfileException(reason);
            });
    boolean own = current != null && current.name().equals(entry.dataProfile());
    DataProfile held = own ? null : DataProfile.acquire(dataDir, entry.dataProfile(), appRoot);
    try {
      Preferences root = DataProfile.nodeFor(appRoot, entry.dataProfile());
      return switch (entry.kind()) {
        case MSS -> removeMss(entry, root, remote, own ? current : held);
        case DATA_PROFILE -> {
          DataProfile.deleteData(appRoot, entry.dataProfile());
          yield Optional.empty();
        }
      };
    } finally {
      if (held != null) {
        held.close();
        if (entry.kind() == Kind.DATA_PROFILE) {
          DataProfile.deleteLockFile(dataDir, entry.dataProfile());
        }
      }
    }
  }

  private Optional<String> removeMss(
      Entry entry, Preferences root, RemoteSignOut remote, DataProfile profile) {
    LocalIdentitySessionStore store =
        LocalIdentitySessionStore.forNode(root.node(LocalIdentitySessionStore.NODE), entry.key());
    Optional<String> warning = Optional.empty();
    if (remote != null) {
      Optional<IdentityTarget> target = store.storedTarget();
      if (target.isEmpty()) {
        warning =
            Optional.of(
                "Destino da identidade desconhecido; a sessão foi apagada só deste computador.");
      } else {
        try {
          remote.signOut(target.get(), store, profile.identityDeviceId());
        } catch (RuntimeException e) {
          warning =
              Optional.of(
                  "A sessão foi apagada deste computador, mas a identidade não confirmou o"
                      + " encerramento no servidor: "
                      + e.getMessage());
        }
      }
    }
    store.clear();
    return warning;
  }

  // ------------------------------------------------------------------ util

  private static String label(Entry entry) {
    return "—".equals(entry.name()) ? entry.account() : entry.name();
  }

  static String shortId(String id) {
    if (id == null || id.isBlank()) {
      return "—";
    }
    return id.length() <= 8 ? id : id.substring(0, 8) + "…";
  }

  private static Preferences child(Preferences root, String name) {
    try {
      return root.nodeExists(name) ? root.node(name) : null;
    } catch (BackingStoreException e) {
      return null;
    }
  }

  private static List<String> children(Preferences node) {
    try {
      return List.of(node.childrenNames());
    } catch (BackingStoreException e) {
      return List.of();
    }
  }
}
