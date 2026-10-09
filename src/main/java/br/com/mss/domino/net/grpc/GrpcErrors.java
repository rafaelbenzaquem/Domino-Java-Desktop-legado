package br.com.mss.domino.net.grpc;

import static br.com.mss.domino.net.AccountRefusedException.ACCOUNT_MENU;

import br.com.mss.domino.net.AccountCredentials;
import br.com.mss.domino.net.AccountRefusedException;
import br.com.mss.domino.net.AccountRefusedException.Reason;
import br.com.mss.domino.net.CredentialException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.Locale;
import java.util.Optional;

/**
 * Explica recusas ligadas à conta MSS (M1) — lição do TchowStrick-Java-Desktop-Legado:BUG-002/003:
 * nunca transformar toda recusa em "sessão expirada"; decidir pela <b>origem</b> (falha local ao
 * obter o acesso × resposta do servidor de jogo) e pelo <b>caso</b>.
 *
 * <p>Respostas do servidor (contrato do Domino:M7):
 *
 * <ul>
 *   <li>{@code UNAUTHENTICATED} "conta MSS necessária neste servidor" → {@link
 *       Reason#SIGN_IN_REQUIRED};
 *   <li>{@code UNAUTHENTICATED} "acesso de jogo inválido ou expirado…" (depois da nova tentativa
 *       com acesso renovado) → {@link Reason#ACCESS_REJECTED};
 *   <li>{@code PERMISSION_DENIED} "conta restrita: confirme o contato…" → {@link
 *       Reason#ACCOUNT_RESTRICTED};
 *   <li>{@code PERMISSION_DENIED} "assento pertence a outra conta" → {@link
 *       Reason#SEAT_OF_OTHER_ACCOUNT};
 *   <li>{@code FAILED_PRECONDITION} "esta conta já ocupa um lugar nesta partida" → {@link
 *       Reason#ALREADY_SEATED};
 *   <li>{@code UNAVAILABLE} "identidade MSS indisponível" → {@link
 *       Reason#IDENTITY_UNAVAILABLE_ON_SERVER}.
 * </ul>
 *
 * <p>Falhas locais ({@link CredentialException} na cadeia de causas: a identidade não renovou a
 * sessão, está fora do ar, conta restrita) preservam a mensagem local. Qualquer outro erro não é
 * recusa de conta: {@link #accountRefusal} devolve vazio e quem chama mantém a mensagem de sempre —
 * servidores sem conta não mudam de comportamento.
 */
public final class GrpcErrors {

  static final String MSS_REQUIRED =
      "Este servidor só aceita jogadores com conta MSS. Entre ou crie a conta em "
          + ACCOUNT_MENU
          + ".";

  static final String MSS_REQUIRED_NOT_CONFIGURED =
      " Nesta janela o servidor está configurado sem conta MSS: use Trocar servidor… e escolha um"
          + " servidor marcado \"(conta MSS)\".";

  static final String ACCESS_REJECTED =
      "O servidor de jogo recusou o acesso da sua conta MSS, mesmo depois de renová-lo. Entre de"
          + " novo na conta (Conta MSS… → Trocar de conta…); se continuar, o problema é do"
          + " servidor — tente mais tarde.";

  static final String CONTACT_RESTRICTED =
      "Conta MSS restrita: o prazo para confirmar o e-mail venceu. Confirme o e-mail em "
          + ACCOUNT_MENU
          + " para voltar a jogar.";

  static final String SEAT_OF_OTHER_ACCOUNT =
      "Este assento pertence a outra conta MSS. Entre com a conta que ocupou o assento, ou escolha"
          + " outra partida.";

  static final String ALREADY_SEATED =
      "Esta conta MSS já ocupa um lugar nesta partida (talvez em outra janela ou outro"
          + " dispositivo). Volte por onde entrou, ou entre com outra conta.";

  static final String IDENTITY_UNAVAILABLE =
      "O serviço de identidade MSS está indisponível para o servidor de jogo agora. Tente"
          + " novamente mais tarde.";

  private GrpcErrors() {}

  /**
   * Recusa de conta contida em {@code cause}, já com a mensagem para o jogador; vazio se a falha
   * não for uma recusa de conta conhecida.
   *
   * @param source origem da credencial enviada na chamada
   */
  public static Optional<AccountRefusedException> accountRefusal(
      Throwable cause, AccountCredentials.Source source) {
    CredentialException local = find(cause, CredentialException.class);
    if (local != null) {
      // Falha ao obter a credencial antes de sair do cliente: a mensagem já é para o jogador.
      Reason reason =
          switch (local.reason()) {
            case UNAUTHENTICATED -> Reason.LOCAL_SESSION_ENDED;
            case PERMISSION_DENIED -> Reason.ACCOUNT_RESTRICTED;
            case UNAVAILABLE -> Reason.LOCAL_IDENTITY_UNAVAILABLE;
          };
      return Optional.of(new AccountRefusedException(reason, local.getMessage(), cause));
    }
    Status status = statusOf(cause);
    if (status == null) {
      return Optional.empty();
    }
    String description = status.getDescription();
    return switch (status.getCode()) {
      case UNAUTHENTICATED -> {
        if (mentions(description, "conta mss necessária")
            || source != AccountCredentials.Source.MSS_IDENTITY) {
          yield refusal(
              Reason.SIGN_IN_REQUIRED,
              source == AccountCredentials.Source.MSS_IDENTITY
                  ? MSS_REQUIRED
                  : MSS_REQUIRED + MSS_REQUIRED_NOT_CONFIGURED,
              cause);
        }
        yield refusal(Reason.ACCESS_REJECTED, withDetail(ACCESS_REJECTED, description), cause);
      }
      case PERMISSION_DENIED -> {
        if (isContactRestriction(status)) {
          yield refusal(Reason.ACCOUNT_RESTRICTED, CONTACT_RESTRICTED, cause);
        }
        if (mentions(description, "outra conta")) {
          yield refusal(Reason.SEAT_OF_OTHER_ACCOUNT, SEAT_OF_OTHER_ACCOUNT, cause);
        }
        yield Optional.empty();
      }
      case FAILED_PRECONDITION ->
          mentions(description, "já ocupa um lugar")
              ? refusal(Reason.ALREADY_SEATED, ALREADY_SEATED, cause)
              : Optional.empty();
      case UNAVAILABLE ->
          mentions(description, "identidade")
              ? refusal(Reason.IDENTITY_UNAVAILABLE_ON_SERVER, IDENTITY_UNAVAILABLE, cause)
              : Optional.empty();
      default -> Optional.empty();
    };
  }

  /**
   * {@code true} se a falha é a recusa do servidor de jogo por contato não confirmado ({@code
   * PERMISSION_DENIED} "conta restrita: confirme o contato…"), e não outra recusa de permissão
   * (assento de outra conta).
   */
  static boolean isContactRestriction(Status status) {
    return status.getCode() == Status.Code.PERMISSION_DENIED
        && find(status.getCause(), CredentialException.class) == null
        && (mentions(status.getDescription(), "restrita")
            || mentions(status.getDescription(), "confirme o contato"));
  }

  /** {@code true} se o servidor de jogo recusou com {@code UNAUTHENTICATED}. */
  static boolean isUnauthenticated(Throwable cause) {
    Status status = statusOf(cause);
    return status != null
        && status.getCode() == Status.Code.UNAUTHENTICATED
        && find(cause, CredentialException.class) == null;
  }

  private static Optional<AccountRefusedException> refusal(
      Reason reason, String message, Throwable cause) {
    return Optional.of(new AccountRefusedException(reason, message, cause));
  }

  private static String withDetail(String message, String description) {
    if (description == null || description.isBlank()) {
      return message;
    }
    return message + " (servidor: " + description.strip() + ")";
  }

  private static boolean mentions(String description, String term) {
    return description != null
        && description.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
  }

  static Status statusOf(Throwable cause) {
    for (Throwable c = cause; c != null; c = c.getCause()) {
      if (c instanceof StatusRuntimeException sre) {
        return sre.getStatus();
      }
      if (c instanceof StatusException se) {
        return se.getStatus();
      }
    }
    return null;
  }

  private static <T extends Throwable> T find(Throwable cause, Class<T> type) {
    for (Throwable c = cause; c != null; c = c.getCause()) {
      if (type.isInstance(c)) {
        return type.cast(c);
      }
    }
    return null;
  }
}
