package br.com.mss.domino.net;

/**
 * Falha ao obter a credencial de conta <b>antes</b> de uma chamada de jogo sair do cliente ({@link
 * AccountCredentials}) — ex.: a identidade MSS não renovou a sessão ou está fora do ar. A mensagem
 * é para o jogador, em português; nunca contém tokens.
 */
public final class CredentialException extends RuntimeException {

  /** Espelha os códigos gRPC que o servidor de jogo usaria para a mesma situação. */
  public enum Reason {
    /** Sem sessão ou sessão expirada/revogada na identidade: entrar novamente. */
    UNAUTHENTICATED,
    /** Conta restrita (contato não confirmado após a carência). */
    PERMISSION_DENIED,
    /** Serviço de identidade fora do ar ou inalcançável a partir deste computador. */
    UNAVAILABLE
  }

  private final Reason reason;

  public CredentialException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public CredentialException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
