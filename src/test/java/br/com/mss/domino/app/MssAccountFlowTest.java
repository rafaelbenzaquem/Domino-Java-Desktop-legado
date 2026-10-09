package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.app.IdentityAccountGateway.AccountState;
import br.com.mss.domino.app.IdentityAccountGateway.Capabilities;
import br.com.mss.domino.app.IdentityAccountGateway.Purpose;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class MssAccountFlowTest {

  /** Respostas roteirizadas do jogador. */
  private static final class ScriptedPrompts implements MssAccountFlow.Prompts {
    MssAccountFlow.SignInChoice signIn;
    String email = "ana@example.com";
    final Deque<String> codes = new ArrayDeque<>();
    boolean confirmAnswer = true;
    boolean resendOnFirstCode;
    final List<Purpose> codePurposes = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    @Override
    public MssAccountFlow.SignInChoice askSignIn() {
      return signIn;
    }

    @Override
    public String askEmail(String title) {
      return email;
    }

    @Override
    public String askCode(String email, Purpose purpose, Runnable resend) {
      codePurposes.add(purpose);
      if (resendOnFirstCode) {
        resendOnFirstCode = false;
        resend.run();
      }
      return codes.poll();
    }

    @Override
    public boolean confirm(String message) {
      return confirmAnswer;
    }

    @Override
    public void info(String message) {
      infos.add(message);
    }

    @Override
    public void warn(String message) {
      warnings.add(message);
    }
  }

  private final FakeIdentityGateway gateway = new FakeIdentityGateway();
  private final ScriptedPrompts prompts = new ScriptedPrompts();
  private final MssAccountFlow flow = new MssAccountFlow(gateway, prompts);

  @Test
  void criarContaEConfirmarEmail() {
    prompts.signIn = new MssAccountFlow.SignInChoice(true, "Ana", "ana@example.com");
    prompts.codes.add("123456");

    var status = flow.signIn().orElseThrow();

    assertEquals(AccountState.ACTIVE, status.state());
    assertEquals(List.of(Purpose.VERIFY_CONTACT), prompts.codePurposes);
    assertTrue(gateway.calls.contains("confirm:ch-1:123456"));
    assertTrue(prompts.warnings.isEmpty());
  }

  @Test
  void cancelarAConfirmacaoMantemASessaoProvisoria() {
    prompts.signIn = new MssAccountFlow.SignInChoice(true, "Ana", "ana@example.com");

    var status = flow.signIn().orElseThrow();

    assertEquals(AccountState.PROVISIONAL, status.state());
  }

  @Test
  void emailJaCadastradoNaoConcedeSessaoESegueParaRecuperacao() {
    gateway.existingEmails.add("ana@example.com");
    prompts.signIn = new MssAccountFlow.SignInChoice(true, "Ana", "ana@example.com");
    prompts.codes.add("123456");

    var status = flow.signIn().orElseThrow();

    assertEquals(List.of(Purpose.RECOVER_ACCOUNT), prompts.codePurposes);
    assertTrue(gateway.calls.contains("requestCode:RECOVER_ACCOUNT"));
    assertEquals("acc-ana@example.com", status.accountId());
  }

  @Test
  void emailJaCadastradoSemAceitarRecuperacaoNaoEntra() {
    gateway.existingEmails.add("ana@example.com");
    prompts.signIn = new MssAccountFlow.SignInChoice(true, "Ana", "ana@example.com");
    prompts.confirmAnswer = false;

    assertTrue(flow.signIn().isEmpty());
    assertNull(gateway.session);
  }

  @Test
  void entrarEmContaExistenteUsaCodigoDeRecuperacao() {
    prompts.signIn = new MssAccountFlow.SignInChoice(false, "", "ana@example.com");
    prompts.codes.add("123456");

    assertTrue(flow.signIn().isPresent());
    assertEquals(List.of(Purpose.RECOVER_ACCOUNT), prompts.codePurposes);
  }

  @Test
  void codigoErradoAvisaEPerguntaDeNovo() {
    prompts.signIn = new MssAccountFlow.SignInChoice(false, "", "ana@example.com");
    prompts.codes.add("000000");
    prompts.codes.add("123456");

    assertTrue(flow.signIn().isPresent());
    assertEquals(1, prompts.warnings.size());
    assertEquals(2, prompts.codePurposes.size());
  }

  @Test
  void reenviarTrocaODesafio() {
    prompts.signIn = new MssAccountFlow.SignInChoice(false, "", "ana@example.com");
    prompts.resendOnFirstCode = true;
    prompts.codes.add("123456");

    assertTrue(flow.signIn().isPresent());
    assertTrue(gateway.calls.contains("recover:ch-2:123456"));
  }

  @Test
  void servidorSoComTelefoneDaMensagemClara() {
    gateway.capabilities = new Capabilities(false, true);

    assertTrue(flow.signIn().isEmpty());
    assertTrue(prompts.warnings.get(0).contains("telefone"));
  }

  @Test
  void identidadeForaDoArViraAvisoEmPortugues() {
    FakeIdentityGateway down =
        new FakeIdentityGateway() {
          @Override
          public Capabilities capabilities() {
            throw error(IdentityAccountException.Kind.UNAVAILABLE);
          }
        };
    var flowDown = new MssAccountFlow(down, prompts);

    assertTrue(flowDown.signIn().isEmpty());
    assertTrue(prompts.warnings.get(0).contains("indisponível"));
  }

  @Test
  void recuperarEConfirmarEmailPedemOEmail() {
    prompts.codes.add("123456");
    assertTrue(flow.recover().isPresent());

    gateway.stateAfterConfirm = AccountState.ACTIVE;
    prompts.codes.add("123456");
    assertEquals(AccountState.ACTIVE, flow.confirmEmail().orElseThrow().state());
    assertEquals(List.of(Purpose.RECOVER_ACCOUNT, Purpose.VERIFY_CONTACT), prompts.codePurposes);
  }

  @Test
  void sairLimpaASessaoMesmoComFalhaDaIdentidade() {
    prompts.codes.add("123456");
    flow.recover();
    gateway.signOutError = FakeIdentityGateway.error(IdentityAccountException.Kind.UNAVAILABLE);

    flow.signOut(true);

    assertTrue(gateway.currentAccount().isEmpty());
    assertTrue(gateway.calls.contains("signOut:true"));
    assertFalse(prompts.warnings.isEmpty());
  }

  @Test
  void perfilRecusaNickVazioEAtualizaAvatar() {
    prompts.codes.add("123456");
    flow.recover();

    assertTrue(flow.updateProfile("  ", "a1").isEmpty());
    var updated = flow.updateProfile(" Ana ", "avatar-7").orElseThrow();

    assertEquals("Ana", updated.nick());
    assertEquals("avatar-7", updated.avatarId());
  }

  @Test
  void estadoSemSessaoEVazio() {
    assertTrue(flow.refreshStatus().isEmpty());
  }

  @Test
  void mensagensDeEstadoSaoClaras() {
    assertTrue(MssAccountFlow.stateMessage(AccountState.PROVISIONAL).contains("provisória"));
    assertTrue(MssAccountFlow.stateMessage(AccountState.ACTIVE).contains("ativa"));
    assertTrue(MssAccountFlow.stateMessage(AccountState.RESTRICTED).contains("restrita"));
  }

  @Test
  void trocarDeContaSaiSoDesteDispositivoEEntraComOutra() {
    gateway.session =
        new IdentityAccountGateway.AccountStatus(
            "acc-ana", AccountState.ACTIVE, java.time.Instant.now().plusSeconds(3600));
    prompts.signIn = new MssAccountFlow.SignInChoice(true, "Bia", "bia@example.com");
    prompts.codes.add("123456");

    var status = flow.switchAccount().orElseThrow();

    assertEquals("acc-bia@example.com", status.accountId());
    assertTrue(gateway.calls.contains("signOut:false"));
    assertFalse(gateway.calls.contains("signOut:true"));
  }

  @Test
  void trocarDeContaSemSessaoSoAbreOEntrar() {
    prompts.signIn = null;

    assertTrue(flow.switchAccount().isEmpty());
    assertFalse(gateway.calls.stream().anyMatch(c -> c.startsWith("signOut")));
  }
}
