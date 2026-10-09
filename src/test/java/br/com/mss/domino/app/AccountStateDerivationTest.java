package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.app.IdentityAccountGateway.AccountState;
import br.com.mss.domino.net.CredentialException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Estado da conta MSS sem resposta da identidade (BUG-003) e textos honestos de envio. */
class AccountStateDerivationTest {

  private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

  private static Optional<AccountState> derive(
      AccountState stored, boolean verified, Optional<Instant> since, Instant now) {
    return IdentityAccountGateway.deriveState(stored, verified, since, now);
  }

  @Test
  void contatoConfirmadoSempreAtiva() {
    assertEquals(
        Optional.of(AccountState.ACTIVE),
        derive(AccountState.RESTRICTED, true, Optional.empty(), T0));
    assertEquals(
        Optional.of(AccountState.ACTIVE),
        derive(AccountState.PROVISIONAL, true, Optional.of(T0), T0));
  }

  @Test
  void restritaGuardadaContinuaRestrita() {
    assertEquals(
        Optional.of(AccountState.RESTRICTED),
        derive(AccountState.RESTRICTED, false, Optional.empty(), T0));
  }

  @Test
  void provisoriaRespeitaACarenciaDeUmaHora() {
    Optional<Instant> since = Optional.of(T0);
    assertEquals(
        Optional.of(AccountState.PROVISIONAL),
        derive(AccountState.PROVISIONAL, false, since, T0.plus(Duration.ofMinutes(59))));
    assertEquals(
        Optional.of(AccountState.RESTRICTED),
        derive(AccountState.PROVISIONAL, false, since, T0.plus(Duration.ofHours(1))));
  }

  @Test
  void provisoriaSemReferenciaPrecisaDaIdentidade() {
    assertTrue(derive(AccountState.PROVISIONAL, false, Optional.empty(), T0).isEmpty());
  }

  @Test
  void recusaPorContatoNoJogoMarcaRestrita() {
    var gateway = new FakeIdentityGateway();
    gateway.session =
        new IdentityAccountGateway.AccountStatus("acc-1", AccountState.PROVISIONAL, T0);

    new IdentityGameCredentials(gateway).accountRestricted();

    assertEquals(AccountState.RESTRICTED, gateway.session.state());
  }

  @Test
  void falhaAoMarcarNaoQuebraAChamada() {
    var gateway =
        new FakeIdentityGateway() {
          @Override
          public void markRestricted() {
            throw new IllegalStateException("store indisponível");
          }
        };

    new IdentityGameCredentials(gateway).accountRestricted();
    assertEquals(
        br.com.mss.domino.net.AccountCredentials.Source.MSS_IDENTITY,
        new IdentityGameCredentials(gateway).source());
    assertEquals(
        CredentialException.Reason.UNAUTHENTICATED,
        IdentityGameCredentials.reasonFor(IdentityAccountException.Kind.NOT_SIGNED_IN));
  }

  @Test
  void avisoDeCadastroNaoAfirmaEnvio() {
    String message = MssAccountFlow.signUpMessage("ana@example.com");

    assertFalse(message.contains("Enviamos"), message);
    assertTrue(message.contains("Se o e-mail ana@example.com estiver correto"), message);
    assertTrue(message.contains("spam"), message);
    assertTrue(message.contains("Peça outro"), message);
  }
}
