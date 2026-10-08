package br.com.mss.domino.app;

/**
 * Um perfil local de jogador (ADR-0018): a {@link PlayerId} (chave interna, nunca sai do
 * dispositivo) mais um {@code displayName} editável — é isto que vai como nick em {@code
 * HostRemote.join}. Vários perfis podem coexistir no mesmo dispositivo (computador compartilhado,
 * LAN party).
 */
public record PlayerProfile(PlayerId id, String displayName) {

  public PlayerProfile {
    if (id == null) {
      throw new IllegalArgumentException("perfil sem id");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("perfil sem nome");
    }
    displayName = displayName.strip();
  }
}
