package br.com.mss.domino.app;

import br.com.mss.domino.net.config.ServerPreset;
import java.util.Optional;

/**
 * Lembra o último servidor escolhido manualmente pelo jogador no {@code ServerPickerDialog}
 * (ADR-0022) — por dispositivo, como {@link ProfileStore}. {@code --server=} explícito na linha de
 * comando continua com prioridade máxima ({@code ConnectionResolver.resolveDefault}); isto só entra
 * depois dele e antes do preset padrão do {@code servers.json}.
 */
public interface ServerChoiceStore {

  /** A última escolha manual, se houver. Vazio se o jogador nunca trocou de servidor. */
  Optional<ServerPreset> lastChoice();

  /** Grava {@code preset} como a escolha atual — chamado toda vez que o jogador troca. */
  void remember(ServerPreset preset);
}
