package br.com.mss.domino.net.grpc;

import br.com.mss.domino.net.AccountCredentials;
import br.com.mss.domino.net.CredentialException;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Anexa {@code authorization: Bearer <acesso de jogo>} a <b>toda</b> chamada de jogo (M1, contrato
 * do Domino:M7), lendo a credencial a cada chamada para que o acesso de curta duração da identidade
 * MSS seja renovado antes de vencer. Sem credencial ({@link AccountCredentials#none()}), nenhum
 * cabeçalho — como antes do M1. Se a credencial não puder ser obtida, a chamada falha localmente
 * com o status gRPC correspondente e a mensagem em português, sem chegar ao servidor. Nunca
 * registra o token.
 */
final class GameCallCredentials implements ClientInterceptor {

  static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final AccountCredentials accountCredentials;

  GameCallCredentials(AccountCredentials accountCredentials) {
    this.accountCredentials = accountCredentials;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
    return new ClientInterceptors.CheckedForwardingClientCall<>(next.newCall(method, options)) {
      @Override
      protected void checkedStart(Listener<RespT> listener, Metadata headers) throws Exception {
        String account;
        try {
          account = accountCredentials.token();
        } catch (CredentialException e) {
          throw toStatus(e).asException();
        }
        if (account != null && !account.isBlank()) {
          headers.put(AUTHORIZATION, "Bearer " + account);
        }
        delegate()
            .start(
                new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                    listener) {
                  @Override
                  public void onClose(Status status, Metadata trailers) {
                    // Recusa do servidor por contato não confirmado: atualiza o estado guardado.
                    if (GrpcErrors.isContactRestriction(status)) {
                      accountCredentials.accountRestricted();
                    }
                    super.onClose(status, trailers);
                  }
                },
                headers);
      }
    };
  }

  static Status toStatus(CredentialException e) {
    Status status =
        switch (e.reason()) {
          case UNAUTHENTICATED -> Status.UNAUTHENTICATED;
          case PERMISSION_DENIED -> Status.PERMISSION_DENIED;
          case UNAVAILABLE -> Status.UNAVAILABLE;
        };
    // A causa local permite ao GrpcErrors preservar a mensagem e não confundir com o servidor.
    return status.withDescription(e.getMessage()).withCause(e);
  }
}
