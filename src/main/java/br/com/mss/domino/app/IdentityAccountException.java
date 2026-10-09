package br.com.mss.domino.app;

import br.com.mss.domino.net.AccountRefusedException;

/**
 * Falha de uma operação da conta MSS ({@link IdentityAccountGateway}). A mensagem é para o jogador,
 * em português, e nunca contém tokens ou códigos.
 */
public final class IdentityAccountException extends RuntimeException {

  /** Onde fica, na janela do Dominó, o acesso à conta MSS (citado nas mensagens). */
  public static final String ACCOUNT_MENU = AccountRefusedException.ACCOUNT_MENU;

  /** Espelha os tipos de erro do {@code identity-client-java}. */
  public enum Kind {
    /** Nenhuma sessão guardada neste perfil local para o destino. */
    NOT_SIGNED_IN,
    UNAUTHENTICATED,
    PERMISSION_DENIED,
    INVALID_ARGUMENT,
    FAILED_PRECONDITION,
    RESOURCE_EXHAUSTED,
    UNAVAILABLE,
    INTERNAL
  }

  private final Kind kind;

  public IdentityAccountException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public IdentityAccountException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /** {@code true} se o jogador precisa entrar de novo (sem sessão ou sessão inválida). */
  public boolean requiresSignIn() {
    return kind == Kind.NOT_SIGNED_IN || kind == Kind.UNAUTHENTICATED;
  }

  /**
   * Mensagem padrão em português para cada tipo. São falhas do <b>serviço de identidade</b> (ou da
   * sessão local), não do servidor de jogo — por isso falam em "identidade MSS".
   */
  public static String defaultMessage(Kind kind) {
    return switch (kind) {
      case NOT_SIGNED_IN ->
          "Você não entrou na conta MSS neste servidor. Entre ou crie a conta em "
              + ACCOUNT_MENU
              + " para jogar.";
      case UNAUTHENTICATED ->
          "Sua sessão da conta MSS expirou ou foi encerrada (a identidade MSS não a renovou)."
              + " Entre de novo em "
              + ACCOUNT_MENU
              + ".";
      case PERMISSION_DENIED ->
          "Conta MSS restrita: o prazo para confirmar o e-mail venceu. Confirme o e-mail em "
              + ACCOUNT_MENU
              + " para continuar jogando.";
      case INVALID_ARGUMENT -> "Dados inválidos. Confira o nick, o e-mail ou o código.";
      case FAILED_PRECONDITION ->
          "Operação não permitida agora (código vencido ou já usado?). Peça um novo código.";
      case RESOURCE_EXHAUSTED ->
          "Muitas tentativas ou envios. Aguarde alguns minutos e tente de novo.";
      case UNAVAILABLE ->
          "Serviço de identidade MSS indisponível: este computador não conseguiu falar com ele."
              + " Verifique a conexão e tente de novo.";
      case INTERNAL -> "Erro inesperado no serviço de identidade MSS.";
    };
  }
}
