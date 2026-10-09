package br.com.mss.domino.app;

import br.com.mss.domino.net.AccountCredentials;
import br.com.mss.domino.net.CredentialException;

/**
 * Credencial de jogo vinda da identidade MSS (M1): cada chamada de jogo pede {@link
 * IdentityAccountGateway#gameAccessToken()} (a biblioteca reaproveita o acesso em cache e o renova
 * antes de expirar). Erros da identidade viram {@link CredentialException} com mensagem em
 * português; o transporte converte para o status gRPC correspondente.
 */
public final class IdentityGameCredentials implements AccountCredentials {

  private final IdentityAccountGateway gateway;

  public IdentityGameCredentials(IdentityAccountGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  public String token() {
    try {
      return gateway.gameAccessToken();
    } catch (IdentityAccountException e) {
      throw new CredentialException(reasonFor(e.kind()), e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new CredentialException(
          CredentialException.Reason.UNAVAILABLE,
          IdentityAccountException.defaultMessage(IdentityAccountException.Kind.UNAVAILABLE),
          e);
    }
  }

  @Override
  public boolean renewAfterRejection() {
    gateway.invalidateGameAccess();
    return true;
  }

  @Override
  public Source source() {
    return Source.MSS_IDENTITY;
  }

  /** O servidor de jogo disse que a conta está restrita: guarda o estado para a UI. */
  @Override
  public void accountRestricted() {
    try {
      gateway.markRestricted();
    } catch (RuntimeException e) {
      // só atualiza o estado exibido; a recusa em si já chega ao jogador
    }
  }

  static CredentialException.Reason reasonFor(IdentityAccountException.Kind kind) {
    return switch (kind) {
      case NOT_SIGNED_IN, UNAUTHENTICATED -> CredentialException.Reason.UNAUTHENTICATED;
      case PERMISSION_DENIED -> CredentialException.Reason.PERMISSION_DENIED;
      default -> CredentialException.Reason.UNAVAILABLE;
    };
  }
}
