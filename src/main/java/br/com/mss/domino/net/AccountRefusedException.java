package br.com.mss.domino.net;

/**
 * Recusa ligada à conta MSS (M1): do servidor de jogo (contrato do Domino:M7) ou da obtenção local
 * do acesso de jogo. A mensagem já é a frase final para o jogador, em português — quem mostra não
 * acrescenta prefixo genérico como "sessão expirada" (lição TchowStrick-Java-Desktop-Legado:
 * BUG-002). {@link #reason()} diz que ação oferecer.
 */
public final class AccountRefusedException extends TransportException {

  /** Onde fica, na janela do Dominó, o acesso à conta MSS (citado nas mensagens). */
  public static final String ACCOUNT_MENU = "Conta MSS… (barra \"Conta MSS\" no topo da janela)";

  /** O que causou a recusa — decide a ação oferecida na interface. */
  public enum Reason {
    /** O servidor exige conta MSS e a chamada saiu sem credencial (não entrou na conta). */
    SIGN_IN_REQUIRED,
    /** O servidor recusou o acesso de jogo mesmo depois de renovado: entrar de novo. */
    ACCESS_REJECTED,
    /** Conta restrita (contato não confirmado): oferecer confirmar agora. */
    ACCOUNT_RESTRICTED,
    /** O servidor de jogo não conseguiu falar com a identidade MSS: tentar mais tarde. */
    IDENTITY_UNAVAILABLE_ON_SERVER,
    /** O assento (token salvo) pertence a outra conta MSS. */
    SEAT_OF_OTHER_ACCOUNT,
    /** Esta conta já ocupa um lugar na partida (outra janela ou dispositivo). */
    ALREADY_SEATED,
    /** A identidade não renovou a sessão local (expirada/revogada): entrar de novo. */
    LOCAL_SESSION_ENDED,
    /** Este computador não conseguiu falar com a identidade MSS. */
    LOCAL_IDENTITY_UNAVAILABLE
  }

  private final Reason reason;

  public AccountRefusedException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  /** {@code true} se a ação certa é entrar (de novo) na conta MSS. */
  public boolean requiresSignIn() {
    return reason == Reason.SIGN_IN_REQUIRED
        || reason == Reason.ACCESS_REJECTED
        || reason == Reason.LOCAL_SESSION_ENDED;
  }
}
