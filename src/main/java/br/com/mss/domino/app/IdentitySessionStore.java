package br.com.mss.domino.app;

import java.time.Instant;
import java.util.Optional;

/**
 * Sessão da conta MSS guardada neste dispositivo para <b>um</b> destino de identidade e um perfil
 * local de dados (M1). O adaptador {@link IdentityClientGateway} a expõe como o {@code
 * SessionStore} do {@code identity-client-java}. Implementações nunca registram o token.
 */
public interface IdentitySessionStore {

  Optional<StoredIdentitySession> load();

  void save(StoredIdentitySession session);

  void clear();

  /**
   * Nick lembrado para a conta {@code accountId}, só para exibir a conta ativa sem consultar a
   * identidade; vazio se não houver ou se a sessão guardada for de outra conta.
   */
  default Optional<String> nickFor(String accountId) {
    return Optional.empty();
  }

  /**
   * Lembra nick e contato mascarado (ex.: {@code an***@***.com}, como a identidade devolve) da
   * conta da sessão guardada, só para exibição local; apagados junto com a sessão.
   */
  default void rememberProfile(String accountId, String nick, String maskedContact) {}

  /** Contato mascarado lembrado para {@code accountId}; vazio se não houver. */
  default Optional<String> maskedContactFor(String accountId) {
    return Optional.empty();
  }

  /**
   * Primeira vez em que este dispositivo viu a conta {@code accountId} como PROVISIONAL (referência
   * para a carência); vazio se não houver ou se for de outra conta.
   */
  default Optional<Instant> provisionalSince(String accountId) {
    return Optional.empty();
  }

  /** Lembra {@link #provisionalSince(String)} (apagado junto com a sessão). */
  default void rememberProvisionalSince(String accountId, Instant at) {}

  /**
   * Sessão guardada. {@code state} é o nome do {@code AccountState} ({@code PROVISIONAL}, {@code
   * ACTIVE}, {@code RESTRICTED}).
   */
  record StoredIdentitySession(
      String sessionToken, String accountId, long expiresAtEpochSeconds, String state) {

    @Override
    public String toString() {
      // Nunca expor o token em logs/depuração.
      return "StoredIdentitySession[accountId="
          + accountId
          + ", expiresAt="
          + expiresAtEpochSeconds
          + ", state="
          + state
          + "]";
    }
  }
}
