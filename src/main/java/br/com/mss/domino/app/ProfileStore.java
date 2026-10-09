package br.com.mss.domino.app;

import java.util.List;
import java.util.Optional;

/**
 * Guarda os perfis locais e qual está ativo (ADR-0018). A implementação atual ({@link
 * LocalProfileStore}) é por dispositivo; um eventual login por conta (ADR-0023) entraria como uma
 * segunda implementação, sem mudar quem chama.
 */
public interface ProfileStore {

  /** Todos os perfis do dispositivo, em ordem de criação. */
  List<PlayerProfile> list();

  /** O perfil ativo, se já houver algum. */
  Optional<PlayerProfile> active();

  Optional<PlayerProfile> find(PlayerId id);

  /** Cria um perfil novo, que passa a ser o ativo. */
  PlayerProfile create(String displayName);

  /** Marca {@code id} como ativo. Ignora ids desconhecidos. */
  void setActive(PlayerId id);
}
