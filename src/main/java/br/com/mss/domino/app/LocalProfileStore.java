package br.com.mss.domino.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * {@link ProfileStore} por dispositivo, sobre {@link Preferences} (ADR-0018) — mesmo padrão do
 * TchowStrick.
 *
 * <p>Layout: um nó-filho por perfil (nome do nó = {@link PlayerId#value()}), com as chaves {@code
 * name} (displayName) e {@code seq} (ordem de criação). O nó-pai guarda {@code active} = id ativo.
 */
public final class LocalProfileStore implements ProfileStore {

  private static final String KEY_NAME = "name";
  private static final String KEY_SEQ = "seq";
  private static final String KEY_ACTIVE = "active";
  private static final String KEY_NEXT_SEQ = "nextSeq";

  private final Preferences root;

  public LocalProfileStore() {
    this(Preferences.userNodeForPackage(LocalProfileStore.class).node("profiles"));
  }

  LocalProfileStore(Preferences root) {
    this.root = root;
  }

  @Override
  public synchronized List<PlayerProfile> list() {
    List<PlayerProfile> profiles = new ArrayList<>();
    for (String id : childNodeNames()) {
      Preferences node = root.node(id);
      profiles.add(new PlayerProfile(new PlayerId(id), node.get(KEY_NAME, id)));
    }
    profiles.sort(Comparator.comparingLong(p -> root.node(p.id().value()).getLong(KEY_SEQ, 0)));
    return List.copyOf(profiles);
  }

  @Override
  public synchronized Optional<PlayerProfile> active() {
    String id = root.get(KEY_ACTIVE, null);
    return id == null ? Optional.empty() : find(new PlayerId(id));
  }

  @Override
  public synchronized Optional<PlayerProfile> find(PlayerId id) {
    try {
      if (!root.nodeExists(id.value())) {
        return Optional.empty();
      }
    } catch (BackingStoreException e) {
      return Optional.empty();
    }
    Preferences node = root.node(id.value());
    return Optional.of(new PlayerProfile(id, node.get(KEY_NAME, id.value())));
  }

  @Override
  public synchronized PlayerProfile create(String displayName) {
    PlayerProfile profile = new PlayerProfile(PlayerId.newLocal(), displayName);
    Preferences node = root.node(profile.id().value());
    node.put(KEY_NAME, profile.displayName());
    node.putLong(KEY_SEQ, root.getLong(KEY_NEXT_SEQ, 0));
    root.putLong(KEY_NEXT_SEQ, root.getLong(KEY_NEXT_SEQ, 0) + 1);
    root.put(KEY_ACTIVE, profile.id().value());
    flush();
    return profile;
  }

  @Override
  public synchronized void setActive(PlayerId id) {
    if (find(id).isEmpty()) {
      return;
    }
    root.put(KEY_ACTIVE, id.value());
    flush();
  }

  private List<String> childNodeNames() {
    try {
      return new ArrayList<>(List.of(root.childrenNames()));
    } catch (BackingStoreException e) {
      return List.of();
    }
  }

  private void flush() {
    try {
      root.flush();
    } catch (BackingStoreException e) {
      // best-effort: o SO grava sozinho ao sair; não vale abortar a UI por isso
    }
  }
}
