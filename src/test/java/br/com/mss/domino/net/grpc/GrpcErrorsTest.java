package br.com.mss.domino.net.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.AccountCredentials.Source;
import br.com.mss.domino.net.AccountRefusedException;
import br.com.mss.domino.net.AccountRefusedException.Reason;
import br.com.mss.domino.net.CredentialException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Mensagem por caso para recusas de conta MSS (M1; lições TchowStrick-Java-Desktop-Legado:
 * BUG-002/003): as descrições do servidor são as do contrato do Domino:M7.
 */
class GrpcErrorsTest {

  private static Optional<AccountRefusedException> refusal(Status status, Source source) {
    return GrpcErrors.accountRefusal(status.asRuntimeException(), source);
  }

  private static AccountRefusedException mss(Status status) {
    return refusal(status, Source.MSS_IDENTITY).orElseThrow();
  }

  @Test
  void contaMssNecessariaPedeParaEntrar() {
    AccountRefusedException e =
        mss(Status.UNAUTHENTICATED.withDescription("conta MSS necessária neste servidor"));

    assertEquals(Reason.SIGN_IN_REQUIRED, e.reason());
    assertTrue(e.requiresSignIn());
    assertTrue(e.getMessage().startsWith("Este servidor só aceita jogadores com conta MSS."));
    assertTrue(e.getMessage().contains("Conta MSS…"));
    assertFalse(e.getMessage().toLowerCase().contains("expirad"), "não fala em sessão expirada");
  }

  @Test
  void contaMssNecessariaNumServidorConfiguradoSemContaSugereTrocarServidor() {
    AccountRefusedException e =
        refusal(
                Status.UNAUTHENTICATED.withDescription("conta MSS necessária neste servidor"),
                Source.NONE)
            .orElseThrow();

    assertEquals(Reason.SIGN_IN_REQUIRED, e.reason());
    assertTrue(e.getMessage().contains("Trocar servidor…"));
  }

  @Test
  void acessoRecusadoDepoisDaRenovacaoPedeParaEntrarDeNovoComODetalhe() {
    AccountRefusedException e =
        mss(
            Status.UNAUTHENTICATED.withDescription(
                "acesso de jogo inválido ou expirado; entre novamente na conta MSS"));

    assertEquals(Reason.ACCESS_REJECTED, e.reason());
    assertTrue(e.getMessage().contains("mesmo depois de renová-lo"));
    assertTrue(e.getMessage().contains("(servidor: acesso de jogo inválido ou expirado"));
    assertFalse(e.getMessage().contains("confirm"), "não manda confirmar e-mail");
  }

  @Test
  void contaRestritaOfereceConfirmar() {
    AccountRefusedException e =
        mss(
            Status.PERMISSION_DENIED.withDescription(
                "conta restrita: confirme o contato na conta MSS"));

    assertEquals(Reason.ACCOUNT_RESTRICTED, e.reason());
    assertTrue(e.getMessage().contains("restrita"));
    assertTrue(e.getMessage().contains("Confirme o e-mail"));
  }

  @Test
  void identidadeIndisponivelNoServidorNaoFalaEmSessaoNemEmail() {
    AccountRefusedException e =
        mss(Status.UNAVAILABLE.withDescription("identidade MSS indisponível"));

    assertEquals(Reason.IDENTITY_UNAVAILABLE_ON_SERVER, e.reason());
    assertTrue(e.getMessage().contains("indisponível para o servidor de jogo"));
    assertTrue(e.getMessage().contains("mais tarde"));
    assertFalse(e.getMessage().contains("sessão"));
    assertFalse(e.getMessage().contains("e-mail"));
  }

  @Test
  void assentoDeOutraContaTemMensagemPropria() {
    AccountRefusedException e =
        mss(Status.PERMISSION_DENIED.withDescription("assento pertence a outra conta"));

    assertEquals(Reason.SEAT_OF_OTHER_ACCOUNT, e.reason());
    assertTrue(e.getMessage().contains("pertence a outra conta MSS"));
    assertFalse(e.requiresSignIn());
  }

  @Test
  void mesmaContaJaSentadaNaPartidaTemMensagemPropria() {
    AccountRefusedException e =
        mss(
            Status.FAILED_PRECONDITION.withDescription(
                "esta conta já ocupa um lugar nesta partida"));

    assertEquals(Reason.ALREADY_SEATED, e.reason());
    assertTrue(e.getMessage().contains("já ocupa um lugar nesta partida"));
    assertFalse(e.requiresSignIn());
  }

  @Test
  void falhaLocalPreservaAMensagemDaIdentidade() {
    CredentialException local =
        new CredentialException(
            CredentialException.Reason.UNAVAILABLE, "Serviço de identidade MSS indisponível: x");
    StatusRuntimeException sre = GameCallCredentials.toStatus(local).asRuntimeException();

    AccountRefusedException e = GrpcErrors.accountRefusal(sre, Source.MSS_IDENTITY).orElseThrow();

    assertEquals(Reason.LOCAL_IDENTITY_UNAVAILABLE, e.reason());
    assertEquals("Serviço de identidade MSS indisponível: x", e.getMessage());
    assertFalse(GrpcErrors.isUnauthenticated(sre), "falha local não é recusa do servidor");
  }

  @Test
  void sessaoLocalEncerradaPedeParaEntrar() {
    StatusRuntimeException sre =
        GameCallCredentials.toStatus(
                new CredentialException(
                    CredentialException.Reason.UNAUTHENTICATED, "entre de novo"))
            .asRuntimeException();

    AccountRefusedException e = GrpcErrors.accountRefusal(sre, Source.MSS_IDENTITY).orElseThrow();

    assertEquals(Reason.LOCAL_SESSION_ENDED, e.reason());
    assertTrue(e.requiresSignIn());
  }

  @Test
  void restricaoLocalNaoContaComoRecusaDoServidor() {
    Status local =
        GameCallCredentials.toStatus(
            new CredentialException(
                CredentialException.Reason.PERMISSION_DENIED, "conta restrita (local)"));

    assertFalse(GrpcErrors.isContactRestriction(local));
    assertTrue(
        GrpcErrors.isContactRestriction(
            Status.PERMISSION_DENIED.withDescription("conta restrita: confirme o contato")));
  }

  @Test
  void errosQueNaoSaoDeContaFicamComoAntes() {
    assertTrue(refusal(Status.UNAVAILABLE, Source.MSS_IDENTITY).isEmpty());
    assertTrue(
        refusal(Status.FAILED_PRECONDITION.withDescription("partida cheia"), Source.MSS_IDENTITY)
            .isEmpty());
    assertTrue(
        refusal(Status.PERMISSION_DENIED.withDescription("outra coisa"), Source.MSS_IDENTITY)
            .isEmpty());
    assertTrue(refusal(Status.NOT_FOUND, Source.NONE).isEmpty());
    assertTrue(GrpcErrors.accountRefusal(new RuntimeException("x"), Source.NONE).isEmpty());
  }
}
