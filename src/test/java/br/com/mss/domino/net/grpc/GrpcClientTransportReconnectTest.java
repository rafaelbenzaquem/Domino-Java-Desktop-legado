package br.com.mss.domino.net.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.dto.MoveDto;
import br.com.mss.domino.net.grpc.proto.CreateMatchRequest;
import br.com.mss.domino.net.grpc.proto.CreateMatchResponse;
import br.com.mss.domino.net.grpc.proto.DominoHostGrpc;
import br.com.mss.domino.net.grpc.proto.GameMode;
import br.com.mss.domino.net.grpc.proto.JoinMessage;
import br.com.mss.domino.net.grpc.proto.JoinRequest;
import br.com.mss.domino.net.grpc.proto.ListMatchesRequest;
import br.com.mss.domino.net.grpc.proto.ListMatchesResponse;
import br.com.mss.domino.net.grpc.proto.ReconnectRequest;
import br.com.mss.domino.net.grpc.proto.SendChatRequest;
import br.com.mss.domino.net.grpc.proto.Session;
import br.com.mss.domino.net.grpc.proto.Snapshot;
import br.com.mss.domino.net.grpc.proto.SubmitMoveRequest;
import com.google.protobuf.Empty;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Retry automático de {@link GrpcClientTransport} ao ver falha de rede em partida (ADR-0019) —
 * sobre um serviço gRPC de mentira, num servidor local de verdade (só assim dá pra controlar
 * exatamente quando cada RPC falha), pra distinguir falha transitória (reconecta e reenvia sozinho)
 * de jogada recusada por regra (propaga na hora, sem tentar reconectar). Substitui o antigo {@code
 * RmiClientTransportReconnectTest} (Fase 6a-4) — mesmos quatro cenários, agora contra o serviço
 * {@code DominoHost} em vez do {@code HostRemote}.
 */
class GrpcClientTransportReconnectTest {

  private Server server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.shutdownNow();
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private GrpcClientTransport startAndJoin(FakeService fake) throws Exception {
    server = ServerBuilder.forPort(freePort()).addService(fake).build().start();
    GrpcClientTransport transport = new GrpcClientTransport("127.0.0.1", server.getPort());
    transport.open();
    transport.join("m1", "Ana");
    return transport;
  }

  @Test
  void quedaTransitoria_reconectaSozinhoEReenviaAJogada() throws Exception {
    FakeService fake = FakeService.submitMoveFailsThenSucceeds(/* failuresBeforeSuccess= */ 1);
    GrpcClientTransport transport = startAndJoin(fake);

    transport.sendMove(MoveDto.pass(0));

    assertEquals(1, fake.reconnectCalls);
    assertEquals(2, fake.submitMoveCalls);
  }

  @Test
  void jogadaRecusadaPorRegra_naoTentaReconectar() throws Exception {
    FakeService fake = FakeService.submitMoveRejects("NOT_YOUR_TURN");
    GrpcClientTransport transport = startAndJoin(fake);

    TransportException ex =
        assertThrows(TransportException.class, () -> transport.sendMove(MoveDto.pass(0)));

    assertEquals(0, fake.reconnectCalls);
    assertEquals(1, fake.submitMoveCalls);
    assertTrue(ex.getMessage().contains("NOT_YOUR_TURN"));
  }

  @Test
  void redeContinuaFora_propagaTransportExceptionAposEsgotarAsTentativas() throws Exception {
    FakeService fake = FakeService.tudoFalha();
    GrpcClientTransport transport = startAndJoin(fake);

    assertThrows(TransportException.class, () -> transport.sendMove(MoveDto.pass(0)));

    assertEquals(2, fake.reconnectCalls); // GrpcClientTransport.RECONNECT_ATTEMPTS
  }

  @Test
  void chatComQuedaTransitoria_reconectaSozinhoEReenviaOChat() throws Exception {
    FakeService fake = FakeService.sendChatFailsThenSucceeds(/* failuresBeforeSuccess= */ 1);
    GrpcClientTransport transport = startAndJoin(fake);

    transport.sendChat("oi");

    assertEquals(1, fake.reconnectCalls);
    assertEquals(2, fake.sendChatCalls);
  }

  private static Session session(String token) {
    return Session.newBuilder()
        .setToken(token)
        .setSeat(0)
        .setSnapshot(Snapshot.newBuilder().setMatchId("m1").setMode(GameMode.FREE_FOR_ALL).build())
        .build();
  }

  /** {@code DominoHostImplBase} de mentira — só implementa o que os testes acima precisam. */
  private static final class FakeService extends DominoHostGrpc.DominoHostImplBase {

    private int submitMoveFailuresRemaining;
    private int sendChatFailuresRemaining;
    private String rejectionReason;
    private boolean reconnectAlwaysFails;

    int reconnectCalls;
    int submitMoveCalls;
    int sendChatCalls;

    static FakeService submitMoveFailsThenSucceeds(int failuresBeforeSuccess) {
      FakeService fake = new FakeService();
      fake.submitMoveFailuresRemaining = failuresBeforeSuccess;
      return fake;
    }

    static FakeService sendChatFailsThenSucceeds(int failuresBeforeSuccess) {
      FakeService fake = new FakeService();
      fake.sendChatFailuresRemaining = failuresBeforeSuccess;
      return fake;
    }

    static FakeService submitMoveRejects(String reason) {
      FakeService fake = new FakeService();
      fake.rejectionReason = reason;
      return fake;
    }

    static FakeService tudoFalha() {
      FakeService fake = new FakeService();
      fake.submitMoveFailuresRemaining = Integer.MAX_VALUE;
      fake.reconnectAlwaysFails = true;
      return fake;
    }

    @Override
    public void join(JoinRequest request, StreamObserver<JoinMessage> responseObserver) {
      responseObserver.onNext(JoinMessage.newBuilder().setSession(session("tok")).build());
      // stream fica aberto (sem onCompleted) — igual ao serviço de verdade, até o cliente sair.
    }

    @Override
    public void reconnect(ReconnectRequest request, StreamObserver<JoinMessage> responseObserver) {
      reconnectCalls++;
      if (reconnectAlwaysFails) {
        responseObserver.onError(
            Status.UNAVAILABLE
                .withDescription("rede ainda fora do ar (simulado)")
                .asRuntimeException());
        return;
      }
      responseObserver.onNext(
          JoinMessage.newBuilder().setSession(session(request.getToken())).build());
    }

    @Override
    public void submitMove(SubmitMoveRequest request, StreamObserver<Empty> responseObserver) {
      submitMoveCalls++;
      if (rejectionReason != null) {
        responseObserver.onError(
            Status.FAILED_PRECONDITION.withDescription(rejectionReason).asRuntimeException());
        return;
      }
      if (submitMoveFailuresRemaining > 0) {
        submitMoveFailuresRemaining--;
        responseObserver.onError(
            Status.UNAVAILABLE.withDescription("rede fora do ar (simulado)").asRuntimeException());
        return;
      }
      responseObserver.onNext(Empty.getDefaultInstance());
      responseObserver.onCompleted();
    }

    @Override
    public void sendChat(SendChatRequest request, StreamObserver<Empty> responseObserver) {
      sendChatCalls++;
      if (sendChatFailuresRemaining > 0) {
        sendChatFailuresRemaining--;
        responseObserver.onError(
            Status.UNAVAILABLE.withDescription("rede fora do ar (simulado)").asRuntimeException());
        return;
      }
      responseObserver.onNext(Empty.getDefaultInstance());
      responseObserver.onCompleted();
    }

    @Override
    public void listMatches(
        ListMatchesRequest request, StreamObserver<ListMatchesResponse> responseObserver) {
      responseObserver.onNext(ListMatchesResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }

    @Override
    public void createMatch(
        CreateMatchRequest request, StreamObserver<CreateMatchResponse> responseObserver) {
      responseObserver.onNext(CreateMatchResponse.newBuilder().setMatchId("m1").build());
      responseObserver.onCompleted();
    }
  }
}
