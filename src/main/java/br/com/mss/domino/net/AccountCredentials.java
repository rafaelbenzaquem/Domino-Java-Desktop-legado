package br.com.mss.domino.net;

/**
 * Fonte da credencial de conta anexada como {@code authorization: Bearer <token>} nas chamadas de
 * jogo (M1 deste repositório, Domino:M7). Consultada <b>a cada chamada</b>, para que um acesso de
 * curta duração (identidade MSS, ~10 min) seja renovado antes de cada RPC, inclusive no meio de uma
 * partida longa.
 *
 * <p>Implementações: {@link #none()} (LAN, embarcado, servidor sem conta — nenhum cabeçalho) e a da
 * identidade MSS na camada {@code app} ({@code IdentityGameCredentials}).
 */
public interface AccountCredentials {

  /** Origem da credencial, para escolher a mensagem certa quando o servidor a recusa. */
  enum Source {
    /** Sem conta (LAN, embarcado ou servidor que não exige conta). */
    NONE,
    /** Acesso de jogo da identidade MSS. */
    MSS_IDENTITY
  }

  /**
   * Token atual; {@code ""} = chamada sem credencial de conta.
   *
   * @throws CredentialException não foi possível obter a credencial (sem sessão, sessão encerrada,
   *     conta restrita, identidade fora do ar).
   */
  String token();

  /**
   * O servidor de jogo recusou o último token com {@code UNAUTHENTICATED}: descarta o acesso em
   * cache. Devolve {@code true} se um novo {@link #token()} pode ser diferente e vale uma única
   * nova tentativa.
   */
  default boolean renewAfterRejection() {
    return false;
  }

  /** Origem da credencial. */
  default Source source() {
    return Source.NONE;
  }

  /** {@code true} se esta fonte nunca fornece credencial. */
  default boolean isEmpty() {
    return source() == Source.NONE;
  }

  /**
   * O servidor de jogo recusou a conta por contato não confirmado ({@code PERMISSION_DENIED}): a
   * fonte pode atualizar o estado guardado da conta. Padrão: nada a fazer.
   */
  default void accountRestricted() {}

  /** Sem conta: nenhum cabeçalho {@code authorization} — o comportamento anterior ao M1. */
  static AccountCredentials none() {
    return () -> "";
  }
}
