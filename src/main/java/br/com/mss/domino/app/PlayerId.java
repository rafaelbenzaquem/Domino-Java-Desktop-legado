package br.com.mss.domino.app;

import java.util.UUID;

/**
 * Identidade estável de um perfil local (ADR-0018) — puramente do lado do cliente. Ao contrário do
 * {@code guest_id} do TchowStrick, este id <b>nunca trafega pela rede</b>: o Domino já tem no
 * {@code session_token} (ADR-0008) a única credencial que o servidor conhece. {@code PlayerId} só
 * chaveia o perfil dentro do {@code Preferences} local.
 *
 * <p>Não use o nick como identidade: nick ({@link PlayerProfile#displayName()}) é texto livre, não
 * é único nem estável.
 */
public record PlayerId(String value) {

  public PlayerId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("PlayerId vazio");
    }
    value = value.strip();
  }

  /** Um id de perfil novo, único por chamada. */
  public static PlayerId newLocal() {
    return new PlayerId("local-" + UUID.randomUUID());
  }
}
