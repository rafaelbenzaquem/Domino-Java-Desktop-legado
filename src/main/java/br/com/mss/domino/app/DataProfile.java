package br.com.mss.domino.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Perfil local de dados de <b>uma janela</b> do cliente (M1): isola a sessão MSS, os tokens de
 * assento e os perfis de jogador de outras janelas abertas no mesmo usuário do sistema operacional.
 * Porta do {@code DataProfile} do TchowStrick-Java-Desktop-Legado, onde duas janelas na mesma conta
 * do SO compartilhavam a sessão MSS (a 2ª sobrescrevia a 1ª, as duas rotacionavam a mesma sessão e
 * a identidade revogava a família).
 *
 * <p>Cada perfil é travado por um arquivo {@code <dados>/perfis/<nome>.lock} ({@link
 * FileChannel#tryLock()}) enquanto a janela estiver aberta: duas instâncias nunca usam o mesmo
 * perfil — e, portanto, nunca a mesma sessão MSS — ao mesmo tempo. A trava é do sistema operacional
 * e some quando o processo termina (inclusive se ele cair).
 *
 * <p>Layout nas {@link Preferences} do usuário: o perfil {@value #DEFAULT_NAME} usa o nó do pacote
 * {@code app} — exatamente onde os dados ficavam antes dos perfis ({@code profiles}, {@code
 * session-tokens}), o que preserva o que já está salvo; os demais ficam em {@code perfis/<nome>}
 * abaixo dele, com os mesmos nós-filhos. A escolha de servidor ({@code serverChoice}) continua
 * comum a todas as janelas.
 */
public final class DataProfile implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(DataProfile.class);

  /** Perfil da primeira janela e dos dados anteriores aos perfis. */
  public static final String DEFAULT_NAME = "padrao";

  /** Prefixo dos perfis escolhidos automaticamente para a 2ª janela em diante. */
  static final String AUTO_PREFIX = "perfil-";

  /** Quantas janelas simultâneas o modo automático aceita. */
  static final int MAX_AUTO_PROFILES = 20;

  /** Propriedade de sistema que troca o diretório de dados (testes e instalações portáteis). */
  public static final String DATA_DIR_PROPERTY = "domino.data.dir";

  static final String PROFILES_NODE = "perfis";
  private static final String KEY_DEVICE_ID = "mssIdentityDeviceId";
  private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

  private final String name;
  private final Preferences root;
  private final FileChannel channel;
  private final FileLock lock;

  private DataProfile(String name, Preferences root, FileChannel channel, FileLock lock) {
    this.name = name;
    this.root = root;
    this.channel = channel;
    this.lock = lock;
  }

  /** {@code ~/.domino}, ou o valor de {@value #DATA_DIR_PROPERTY}. */
  public static Path defaultDataDir() {
    String override = System.getProperty(DATA_DIR_PROPERTY);
    if (override != null && !override.isBlank()) {
      return Path.of(override.strip());
    }
    return Path.of(System.getProperty("user.home"), ".domino");
  }

  /**
   * Abre o perfil desta janela: {@code requested} explícito ({@code --perfil=}), ou o primeiro
   * livre entre {@value #DEFAULT_NAME}, {@code perfil-2}, {@code perfil-3}…
   *
   * @throws DataProfileException perfil pedido já aberto em outra janela, nome inválido ou janelas
   *     demais.
   */
  public static DataProfile acquire(Path dataDir, String requested) {
    return acquire(dataDir, requested, Preferences.userNodeForPackage(DataProfile.class));
  }

  static DataProfile acquire(Path dataDir, String requested, Preferences appRoot) {
    Path lockDir = dataDir.resolve(PROFILES_NODE);
    try {
      Files.createDirectories(lockDir);
    } catch (IOException e) {
      // Sem diretório de dados não há trava; segue como antes dos perfis, mas avisa.
      String fallback = requested == null ? DEFAULT_NAME : normalize(requested);
      LOG.warn(
          "não foi possível criar {} ({}); perfil local {} aberto sem trava",
          lockDir,
          e.getMessage(),
          fallback);
      return new DataProfile(fallback, nodeFor(appRoot, fallback), null, null);
    }
    if (requested != null) {
      String wanted = normalize(requested);
      DataProfile profile = tryOpen(lockDir, wanted, appRoot);
      if (profile == null) {
        throw new DataProfileException(
            "O perfil local \""
                + displayName(wanted)
                + "\" já está aberto em outra janela do Dominó. Feche-a ou use outro --perfil=.");
      }
      return profile;
    }
    for (int i = 1; i <= MAX_AUTO_PROFILES; i++) {
      String candidate = i == 1 ? DEFAULT_NAME : AUTO_PREFIX + i;
      DataProfile profile = tryOpen(lockDir, candidate, appRoot);
      if (profile != null) {
        return profile;
      }
    }
    throw new DataProfileException(
        "Há "
            + MAX_AUTO_PROFILES
            + " janelas do Dominó abertas neste usuário. Feche alguma para abrir outra.");
  }

  private static DataProfile tryOpen(Path lockDir, String name, Preferences appRoot) {
    Path file = lockDir.resolve(name + ".lock");
    FileChannel channel = null;
    try {
      channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock lock = channel.tryLock();
      if (lock == null) {
        channel.close();
        return null;
      }
      return new DataProfile(name, nodeFor(appRoot, name), channel, lock);
    } catch (OverlappingFileLockException e) {
      // Já travado por esta mesma JVM (outra janela no mesmo processo, ou teste).
      closeQuietly(channel);
      return null;
    } catch (IOException e) {
      closeQuietly(channel);
      LOG.warn("não foi possível travar o perfil local {}: {}", name, e.getMessage());
      return null;
    }
  }

  /**
   * {@code true} se outra janela (outro processo, ou outra instância nesta JVM) segura a trava do
   * perfil {@code name}. Sem arquivo de trava, ninguém o usa.
   */
  static boolean lockedElsewhere(Path dataDir, String name) {
    Path file = dataDir.resolve(PROFILES_NODE).resolve(name + ".lock");
    if (!Files.isRegularFile(file)) {
      return false;
    }
    try (FileChannel probeChannel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      FileLock probe = probeChannel.tryLock();
      if (probe == null) {
        return true;
      }
      probe.release();
      return false;
    } catch (OverlappingFileLockException e) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Perfis locais conhecidos neste usuário: o padrão, os que têm dados nas Preferences e os que já
   * criaram arquivo de trava. Ordem: padrão primeiro, depois alfabética.
   */
  static List<String> knownNames(Path dataDir, Preferences appRoot) {
    SortedSet<String> names = new TreeSet<>();
    try {
      if (appRoot.nodeExists(PROFILES_NODE)) {
        names.addAll(List.of(appRoot.node(PROFILES_NODE).childrenNames()));
      }
    } catch (BackingStoreException e) {
      // segue com o que houver no diretório
    }
    Path lockDir = dataDir.resolve(PROFILES_NODE);
    if (Files.isDirectory(lockDir)) {
      try (var files = Files.list(lockDir)) {
        files
            .map(p -> p.getFileName().toString())
            .filter(n -> n.endsWith(".lock"))
            .map(n -> n.substring(0, n.length() - ".lock".length()))
            .filter(n -> VALID_NAME.matcher(n).matches())
            .forEach(names::add);
      } catch (IOException e) {
        // segue com o que houver nas Preferences
      }
    }
    names.remove(DEFAULT_NAME);
    names.remove(PROFILES_NODE);
    List<String> result = new ArrayList<>();
    result.add(DEFAULT_NAME);
    result.addAll(names);
    return result;
  }

  /** Apaga um perfil local inteiro (não o padrão), já travado por quem chama. */
  static void deleteData(Preferences appRoot, String name) {
    if (DEFAULT_NAME.equals(name)) {
      throw new DataProfileException("O perfil local padrão não pode ser removido.");
    }
    try {
      if (appRoot.nodeExists(PROFILES_NODE) && appRoot.node(PROFILES_NODE).nodeExists(name)) {
        appRoot.node(PROFILES_NODE).node(name).removeNode();
        appRoot.flush();
      }
    } catch (BackingStoreException e) {
      throw new IllegalStateException("não foi possível remover o perfil local " + name, e);
    }
  }

  static void deleteLockFile(Path dataDir, String name) {
    try {
      Files.deleteIfExists(dataDir.resolve(PROFILES_NODE).resolve(name + ".lock"));
    } catch (IOException e) {
      // outra janela abriu o perfil entre a remoção e aqui; o arquivo vazio não faz mal
    }
  }

  static Preferences nodeFor(Preferences appRoot, String name) {
    return DEFAULT_NAME.equals(name) ? appRoot : appRoot.node(PROFILES_NODE).node(name);
  }

  /**
   * Nome canônico: minúsculas, sem acento, 1–32 caracteres {@code [a-z0-9_-]} começando por letra
   * ou dígito. {@code padrão} vira {@value #DEFAULT_NAME}.
   *
   * @throws DataProfileException nome inválido.
   */
  public static String normalize(String raw) {
    String value =
        Normalizer.normalize(raw == null ? "" : raw.strip(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
    if (!VALID_NAME.matcher(value).matches() || value.equals(PROFILES_NODE)) {
      throw new DataProfileException(
          "Nome de perfil local inválido: \""
              + raw
              + "\". Use até 32 letras sem acento, dígitos, '-' ou '_'.");
    }
    return value;
  }

  static String displayName(String name) {
    return DEFAULT_NAME.equals(name) ? "padrão" : name;
  }

  public String name() {
    return name;
  }

  /** "padrão" para o perfil padrão; o próprio nome nos demais. */
  public String displayName() {
    return displayName(name);
  }

  public boolean isDefault() {
    return DEFAULT_NAME.equals(name);
  }

  /** {@code true} se a janela segura a trava do perfil (falso só sem diretório de dados). */
  public boolean locked() {
    return lock != null && lock.isValid();
  }

  /** Nó-filho deste perfil (ex.: {@code profiles}, {@code session-tokens}). */
  Preferences node(String child) {
    return root.node(child);
  }

  /**
   * Identificador deste perfil como "dispositivo" para a identidade MSS (sair deste dispositivo
   * revoga só as sessões dele). Fica no perfil, não nas preferências globais (lição do
   * TchowStrick-Java-Desktop-Legado:BUG-004).
   */
  public String identityDeviceId() {
    synchronized (DataProfile.class) {
      String stored = root.get(KEY_DEVICE_ID, null);
      if (stored == null || stored.isBlank()) {
        stored = UUID.randomUUID().toString();
        root.put(KEY_DEVICE_ID, stored);
      }
      return stored;
    }
  }

  /** Libera a trava (o sistema operacional também a libera quando o processo termina). */
  @Override
  public void close() {
    try {
      if (lock != null && lock.isValid()) {
        lock.release();
      }
    } catch (IOException e) {
      // segue fechando o canal
    }
    closeQuietly(channel);
  }

  private static void closeQuietly(FileChannel channel) {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // nada a fazer
      }
    }
  }

  @Override
  public String toString() {
    return "DataProfile[" + name + (locked() ? "" : ", sem trava") + "]";
  }
}
