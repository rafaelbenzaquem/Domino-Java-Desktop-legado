package br.com.mss.domino.net.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.grpc.proto.DominoHostGrpc;
import br.com.mss.domino.net.grpc.proto.ListMatchesRequest;
import br.com.mss.domino.net.grpc.proto.ListMatchesResponse;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Canal TLS do cliente (M6-02, ADR-0030) contra um servidor gRPC com certificado de teste para
 * {@code localhost}, assinado por uma CA própria ({@code src/test/resources/tls/}, só para teste,
 * válidos até 2126). A validação de certificado é sempre obrigatória: sem a CA certa, recusa.
 */
class GrpcClientTransportTlsTest {

  private Server server;

  @BeforeEach
  void startTlsServer() throws IOException, URISyntaxException {
    TlsServerCredentials.Builder credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(resource("server.pem"), resource("server.key"));
    server =
        Grpc.newServerBuilderForPort(0, credentials.build())
            .addService(new EmptyLobby())
            .build()
            .start();
  }

  @AfterEach
  void stopServer() throws InterruptedException {
    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void conectaComTlsQuandoConfiaNaCaDeDesenvolvimento() throws Exception {
    GrpcClientTransport client = client(true, resource("ca.pem").getPath());
    try {
      client.open();
      assertEquals(List.of(), client.listMatches());
    } finally {
      client.close();
    }
  }

  @Test
  void recusaCertificadoQueACadeiaPadraoDaJvmNaoConhece() throws Exception {
    GrpcClientTransport client = client(true, null);
    try {
      client.open();
      assertHandshakeRefused(assertThrows(TransportException.class, client::listMatches));
    } finally {
      client.close();
    }
  }

  @Test
  void recusaCertificadoAssinadoPorOutraCa() throws Exception {
    GrpcClientTransport client = client(true, resource("other-ca.pem").getPath());
    try {
      client.open();
      assertHandshakeRefused(assertThrows(TransportException.class, client::listMatches));
    } finally {
      client.close();
    }
  }

  @Test
  void textoPuroNaoFalaComServidorTls() throws Exception {
    GrpcClientTransport client = client(false, resource("ca.pem").getPath());
    try {
      client.open();
      assertThrows(TransportException.class, client::listMatches);
    } finally {
      client.close();
    }
  }

  @Test
  void caDeDesenvolvimentoInexistenteFalhaAoAbrirComMensagemClara() {
    GrpcClientTransport client = client(true, "nao-existe/ca.pem");

    TransportException e = assertThrows(TransportException.class, client::open);
    assertTrue(e.getMessage().contains("CA de desenvolvimento"), e.getMessage());
  }

  /** A recusa tem de vir da validação do certificado, não de outra falha qualquer de rede. */
  private static void assertHandshakeRefused(TransportException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SSLHandshakeException) {
        return;
      }
    }
    throw new AssertionError("esperava SSLHandshakeException na causa", e);
  }

  private GrpcClientTransport client(boolean tls, String devCaFile) {
    return new GrpcClientTransport("localhost", server.getPort(), null, tls, devCaFile);
  }

  private static File resource(String name) throws URISyntaxException {
    return new File(GrpcClientTransportTlsTest.class.getResource("/tls/" + name).toURI());
  }

  /** Só o necessário para um RPC unário: lobby vazio. */
  private static final class EmptyLobby extends DominoHostGrpc.DominoHostImplBase {
    @Override
    public void listMatches(
        ListMatchesRequest request, StreamObserver<ListMatchesResponse> response) {
      response.onNext(ListMatchesResponse.getDefaultInstance());
      response.onCompleted();
    }
  }
}
