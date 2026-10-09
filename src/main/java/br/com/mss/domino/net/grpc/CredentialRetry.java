package br.com.mss.domino.net.grpc;

import br.com.mss.domino.net.AccountCredentials;
import br.com.mss.domino.net.config.IdentityTarget;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;

/**
 * Regras comuns às chamadas autenticadas (M1): no máximo <b>uma</b> nova tentativa quando o
 * servidor de jogo recusa a credencial com {@code UNAUTHENTICATED} e a fonte consegue renová-la
 * (sem laço); e credencial de conta só sobre TLS, salvo para a própria máquina (desenvolvimento).
 */
final class CredentialRetry {

  private CredentialRetry() {}

  static <T> T call(AccountCredentials credentials, Supplier<T> rpc) {
    try {
      return rpc.get();
    } catch (StatusRuntimeException e) {
      if (shouldRetry(credentials, e)) {
        return rpc.get();
      }
      throw e;
    }
  }

  static void run(AccountCredentials credentials, Runnable rpc) {
    call(
        credentials,
        () -> {
          rpc.run();
          return null;
        });
  }

  /**
   * {@code true} só para {@code UNAUTHENTICATED} do servidor com credencial renovável — e, nesse
   * caso, já descartou o acesso em cache ({@link AccountCredentials#renewAfterRejection()}).
   */
  static boolean shouldRetry(AccountCredentials credentials, Throwable error) {
    return !credentials.isEmpty()
        && GrpcErrors.isUnauthenticated(error)
        && credentials.renewAfterRejection();
  }

  /** {@code null} se pode enviar; senão a mensagem do motivo. */
  static String plaintextViolation(AccountCredentials credentials, String host, boolean tls) {
    if (credentials.isEmpty() || tls || IdentityTarget.isLoopbackHost(host)) {
      return null;
    }
    return "A conta MSS exige conexão segura (TLS) com o servidor de jogo; texto puro só em"
        + " localhost.";
  }
}
