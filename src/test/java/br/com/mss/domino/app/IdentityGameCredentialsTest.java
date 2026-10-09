package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.CredentialException;
import org.junit.jupiter.api.Test;

class IdentityGameCredentialsTest {

  private final FakeIdentityGateway gateway = new FakeIdentityGateway();
  private final IdentityGameCredentials credentials = new IdentityGameCredentials(gateway);

  @Test
  void pedeOAcessoACadaChamada() {
    assertEquals("access-0", credentials.token());
    assertEquals("access-0", credentials.token());

    assertEquals(2, gateway.calls.stream().filter("gameAccess"::equals).count());
    assertFalse(credentials.isEmpty());
  }

  @Test
  void recusaDoServidorDescartaOAcessoEPermiteNovaTentativa() {
    assertTrue(credentials.renewAfterRejection());

    assertEquals("access-1", credentials.token());
    assertTrue(gateway.calls.contains("invalidate"));
  }

  @Test
  void contaRestritaViraPermissionDenied() {
    gateway.nextAccessError =
        FakeIdentityGateway.error(IdentityAccountException.Kind.PERMISSION_DENIED);

    CredentialException e = assertThrows(CredentialException.class, credentials::token);

    assertEquals(CredentialException.Reason.PERMISSION_DENIED, e.reason());
    assertTrue(e.getMessage().contains("restrita"));
  }

  @Test
  void semSessaoViraUnauthenticated() {
    gateway.nextAccessError =
        FakeIdentityGateway.error(IdentityAccountException.Kind.NOT_SIGNED_IN);

    assertEquals(
        CredentialException.Reason.UNAUTHENTICATED,
        assertThrows(CredentialException.class, credentials::token).reason());
  }

  @Test
  void identidadeForaDoArViraUnavailable() {
    gateway.nextAccessError = FakeIdentityGateway.error(IdentityAccountException.Kind.UNAVAILABLE);

    CredentialException e = assertThrows(CredentialException.class, credentials::token);

    assertEquals(CredentialException.Reason.UNAVAILABLE, e.reason());
    assertTrue(e.getMessage().contains("indisponível"));
  }
}
