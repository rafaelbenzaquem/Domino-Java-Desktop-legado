package br.com.mss.domino.app;

import br.com.mss.domino.net.config.ServerDirectory;
import br.com.mss.domino.net.config.ServerPreset;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * {@link ServerChoiceStore} por dispositivo, sobre {@link Preferences} — mesmo padrão de {@link
 * LocalProfileStore}. Um nó só, chaves {@code name}/{@code host}/{@code port}/{@code tls}; sem
 * porta gravada (nunca trocou), {@link #lastChoice()} devolve vazio. Escolha gravada antes do M6-02
 * não tem {@code tls} e volta como texto puro, como era. {@code official} não é gravado: sai do
 * catálogo embutido ({@link ServerDirectory#isOfficialEndpoint}), nunca do que está no dispositivo.
 */
public final class LocalServerChoiceStore implements ServerChoiceStore {

  private static final String KEY_NAME = "name";
  private static final String KEY_HOST = "host";
  private static final String KEY_PORT = "port";
  private static final String KEY_TLS = "tls";

  private final Preferences node;

  public LocalServerChoiceStore() {
    this(Preferences.userNodeForPackage(LocalServerChoiceStore.class).node("serverChoice"));
  }

  LocalServerChoiceStore(Preferences node) {
    this.node = node;
  }

  @Override
  public synchronized Optional<ServerPreset> lastChoice() {
    String host = node.get(KEY_HOST, null);
    int port = node.getInt(KEY_PORT, 0);
    if (host == null || host.isBlank() || port <= 0) {
      return Optional.empty();
    }
    String name = node.get(KEY_NAME, host);
    boolean tls = node.getBoolean(KEY_TLS, false);
    boolean official = ServerDirectory.isOfficialEndpoint(host, port, tls);
    return Optional.of(new ServerPreset(name, host, port, true, tls, official));
  }

  @Override
  public synchronized void remember(ServerPreset preset) {
    node.put(KEY_NAME, preset.name());
    node.put(KEY_HOST, preset.host());
    node.putInt(KEY_PORT, preset.port());
    node.putBoolean(KEY_TLS, preset.tls());
    try {
      node.flush();
    } catch (BackingStoreException e) {
      // best-effort: o SO grava sozinho ao sair; não vale abortar a UI por isso
    }
  }
}
