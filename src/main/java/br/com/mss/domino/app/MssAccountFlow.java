package br.com.mss.domino.app;

import br.com.mss.domino.app.IdentityAccountGateway.AccountState;
import br.com.mss.domino.app.IdentityAccountGateway.AccountStatus;
import br.com.mss.domino.app.IdentityAccountGateway.Capabilities;
import br.com.mss.domino.app.IdentityAccountGateway.Challenge;
import br.com.mss.domino.app.IdentityAccountGateway.Profile;
import br.com.mss.domino.app.IdentityAccountGateway.Purpose;
import br.com.mss.domino.app.IdentityAccountGateway.SignUp;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fluxos da conta MSS no desktop (M1): entrar/criar com e-mail, confirmar e-mail, recuperar, perfil
 * e sair. Sem Swing: a interação com o jogador passa por {@link Prompts}, e a identidade por {@link
 * IdentityAccountGateway} — os dois são substituídos por fakes nos testes. Porta do fluxo do
 * TchowStrick-Java-Desktop-Legado, com as correções do BUG-003 de lá (não afirma envio do código).
 *
 * <p>Erros da identidade viram {@link Prompts#warn} com mensagem em português; nenhum método lança
 * {@link IdentityAccountException}.
 */
public final class MssAccountFlow {

  /** Interação com o jogador. {@code null} em qualquer pergunta = cancelou. */
  public interface Prompts {

    /** Nick + e-mail e se é conta nova ou entrada numa conta existente. */
    SignInChoice askSignIn();

    /** Pede só o e-mail (confirmar contato, recuperar). */
    String askEmail(String title);

    /**
     * Pede o código enviado a {@code email}. {@code resend} pede um novo código. Devolve {@code
     * null} se o jogador cancelar.
     */
    String askCode(String email, Purpose purpose, Runnable resend);

    boolean confirm(String message);

    void info(String message);

    void warn(String message);
  }

  /** {@code newAccount = false}: entrar numa conta existente (código de recuperação). */
  public record SignInChoice(boolean newAccount, String nick, String email) {}

  private final IdentityAccountGateway gateway;
  private final Prompts prompts;

  public MssAccountFlow(IdentityAccountGateway gateway, Prompts prompts) {
    this.gateway = gateway;
    this.prompts = prompts;
  }

  /** Texto do estado da conta para o jogador. */
  public static String stateMessage(AccountState state) {
    return switch (state) {
      case PROVISIONAL ->
          "Conta provisória: confirme seu e-mail dentro do prazo (1 hora) para continuar jogando.";
      case ACTIVE -> "Conta ativa: e-mail confirmado.";
      case RESTRICTED ->
          "Conta restrita: o prazo para confirmar o e-mail venceu. Confirme o e-mail (Conta MSS… →"
              + " Confirmar e-mail…) para voltar a jogar.";
    };
  }

  /** Rótulo curto do estado, para a barra "Conta MSS". */
  public static String stateLabel(AccountState state) {
    return switch (state) {
      case PROVISIONAL -> "provisória";
      case ACTIVE -> "ativa";
      case RESTRICTED -> "restrita";
    };
  }

  /**
   * Aviso depois de criar a conta. Não afirma que o código foi enviado: a identidade responde igual
   * quando não consegue entregar (anti-enumeração), então só promete o código se o e-mail estiver
   * correto (lição TchowStrick-Java-Desktop-Legado:BUG-003).
   */
  static String signUpMessage(String email) {
    return "Conta MSS criada. Se o e-mail "
        + email
        + " estiver correto, você receberá um código em instantes; confira o spam. Sem código?"
        + " Peça outro. Confirme o e-mail para continuar jogando depois da carência de 1 hora.";
  }

  /**
   * Entrar ou criar conta. Devolve a conta com sessão guardada, ou vazio se o jogador desistiu ou
   * houve erro (já avisado).
   */
  public Optional<AccountStatus> signIn() {
    try {
      Capabilities caps = gateway.capabilities();
      if (!caps.email()) {
        prompts.warn(
            caps.phone()
                ? "Este serviço de identidade só aceita telefone, que ainda não é suportado no"
                    + " desktop."
                : "Cadastro de conta MSS indisponível neste servidor.");
        return Optional.empty();
      }
      SignInChoice choice = prompts.askSignIn();
      if (choice == null || isBlank(choice.email())) {
        return Optional.empty();
      }
      String email = choice.email().strip();
      if (!choice.newAccount()) {
        return codeFlow(email, gateway.requestCode(email, Purpose.RECOVER_ACCOUNT));
      }
      if (isBlank(choice.nick())) {
        prompts.warn("Informe um nick para a conta nova.");
        return Optional.empty();
      }
      SignUp signUp = gateway.signUp(choice.nick().strip(), email);
      if (signUp.account().isPresent()) {
        prompts.info(signUpMessage(email));
        Challenge verify =
            signUp
                .challenge()
                .filter(c -> c.purpose() == Purpose.VERIFY_CONTACT)
                .orElseGet(() -> gateway.requestCode(email, Purpose.VERIFY_CONTACT));
        // Cancelar a confirmação não desfaz a conta: a sessão provisória já vale.
        return codeFlow(email, verify).or(signUp::account);
      }
      if (!prompts.confirm("Este e-mail já tem conta MSS. Enviar um código para entrar nela?")) {
        return Optional.empty();
      }
      Challenge recover =
          signUp
              .challenge()
              .filter(c -> c.purpose() == Purpose.RECOVER_ACCOUNT)
              .orElseGet(() -> gateway.requestCode(email, Purpose.RECOVER_ACCOUNT));
      return codeFlow(email, recover);
    } catch (IdentityAccountException e) {
      prompts.warn(e.getMessage());
      return Optional.empty();
    }
  }

  /** Recuperar a conta (outro dispositivo, sessão perdida): e-mail → código → nova sessão. */
  public Optional<AccountStatus> recover() {
    return emailCodeFlow("Recuperar conta MSS", Purpose.RECOVER_ACCOUNT);
  }

  /** Confirmar o e-mail da conta (sai de PROVISIONAL/RESTRICTED). */
  public Optional<AccountStatus> confirmEmail() {
    return emailCodeFlow("Confirmar e-mail da conta MSS", Purpose.VERIFY_CONTACT);
  }

  /** Estado atualizado (renova a sessão se preciso); vazio se não entrou ou erro (avisado). */
  public Optional<AccountStatus> refreshStatus() {
    if (gateway.currentAccount().isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(gateway.refreshStatus());
    } catch (IdentityAccountException e) {
      prompts.warn(e.getMessage());
      return gateway.currentAccount();
    }
  }

  public Optional<Profile> profile() {
    try {
      return Optional.of(gateway.profile());
    } catch (IdentityAccountException e) {
      prompts.warn(e.getMessage());
      return Optional.empty();
    }
  }

  /** {@code null} mantém o valor atual. */
  public Optional<Profile> updateProfile(String nick, String avatarId) {
    if (nick != null && nick.isBlank()) {
      prompts.warn("O nick não pode ficar vazio.");
      return Optional.empty();
    }
    try {
      return Optional.of(
          gateway.updateProfile(
              nick == null ? null : nick.strip(), avatarId == null ? null : avatarId.strip()));
    } catch (IdentityAccountException e) {
      prompts.warn(e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Sai da conta neste dispositivo ou em todos. A sessão local é apagada mesmo se a identidade
   * falhar; nesse caso o jogador é avisado de que outros dispositivos podem continuar ativos.
   */
  public void signOut(boolean allDevices) {
    try {
      gateway.signOut(allDevices);
      prompts.info(
          allDevices
              ? "Você saiu da conta MSS em todos os dispositivos."
              : "Você saiu da conta MSS neste dispositivo.");
    } catch (IdentityAccountException e) {
      prompts.warn(
          "A sessão local foi apagada, mas a identidade não confirmou o encerramento: "
              + e.getMessage());
    }
  }

  /**
   * "Trocar de conta": sai da conta atual só neste dispositivo (perfil local) e abre o entrar/criar
   * para outra conta. Vazio se o jogador desistir (fica sem conta).
   */
  public Optional<AccountStatus> switchAccount() {
    if (gateway.currentAccount().isPresent()) {
      signOut(false);
    }
    return signIn();
  }

  private Optional<AccountStatus> emailCodeFlow(String title, Purpose purpose) {
    String email = prompts.askEmail(title);
    if (isBlank(email)) {
      return Optional.empty();
    }
    String value = email.strip();
    try {
      return codeFlow(value, gateway.requestCode(value, purpose));
    } catch (IdentityAccountException e) {
      prompts.warn(e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Pede o código até o jogador acertar ou cancelar; código errado/vencido avisa e pergunta de novo
   * (o reenvio troca o desafio).
   */
  private Optional<AccountStatus> codeFlow(String email, Challenge initial) {
    AtomicReference<Challenge> current = new AtomicReference<>(initial);
    Purpose purpose = initial.purpose();
    Runnable resend =
        () -> {
          try {
            current.set(gateway.requestCode(email, purpose));
            prompts.info(
                "Novo código pedido. Se o e-mail estiver correto, ele chega em instantes (sujeito"
                    + " aos limites de envio); confira o spam.");
          } catch (IdentityAccountException e) {
            prompts.warn(e.getMessage());
          }
        };
    while (true) {
      String code = prompts.askCode(email, purpose, resend);
      if (isBlank(code)) {
        return Optional.empty();
      }
      try {
        AccountStatus status =
            purpose == Purpose.RECOVER_ACCOUNT
                ? gateway.recover(email, current.get(), code.strip())
                : gateway.confirmContact(email, current.get(), code.strip());
        prompts.info(
            (purpose == Purpose.RECOVER_ACCOUNT
                    ? "Você entrou na conta MSS. "
                    : "E-mail confirmado. ")
                + stateMessage(status.state()));
        return Optional.of(status);
      } catch (IdentityAccountException e) {
        prompts.warn(e.getMessage());
        if (e.kind() != IdentityAccountException.Kind.INVALID_ARGUMENT
            && e.kind() != IdentityAccountException.Kind.FAILED_PRECONDITION) {
          return Optional.empty();
        }
      }
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
