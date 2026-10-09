package br.com.mss.domino.net.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.AccountCredentials;
import br.com.mss.domino.net.AccountRefusedException;
import br.com.mss.domino.net.AccountRefusedException.Reason;
import br.com.mss.domino.net.CredentialException;
import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.grpc.proto.CreateMatchRequest;
import br.com.mss.domino.net.grpc.proto.CreateMatchResponse;
import br.com.mss.domino.net.grpc.proto.DominoHostGrpc;
import br.com.mss.domino.net.grpc.proto.GetMyStatsRequest;
import br.com.mss.domino.net.grpc.proto.JoinMessage;
import br.com.mss.domino.net.grpc.proto.JoinRequest;
import br.com.mss.domino.net.grpc.proto.ListMatchesRequest;
import br.com.mss.domino.net.grpc.proto.ListMatchesResponse;
import br.com.mss.domino.net.grpc.proto.PlayerStats;
import br.com.mss.domino.net.grpc.proto.ReconnectRequest;
import br.com.mss.domino.net.grpc.proto.Session;
import br.com.mss.domino.net.grpc.proto.Snapshot;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Conta MSS no transporte (M1, contrato do Domino:M7): {@code authorization: Bearer} em toda
 * chamada, uma única nova tentativa com acesso renovado, mensagens por caso, keepalive e reabertura
 * do stream — contra um serviço {@code DominoHost} de mentira num servidor local real.
 */
class GrpcClientTransportAccountTest {

  private static final String EXPIRED =
      "acesso de jogo inválido ou expirado; entre novamente na conta MSS";

  private Server server;
  private GrpcClientTransport transport;

  @AfterEach
  void tearDown() {
    if (transport != null) {
      transport.close();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private GrpcClientTransport start(FakeService fake, AccountCredentials credentials)
      throws Exception {
    server =
        ServerBuilder.forPort(freePort())
            .addService(ServerInterceptors.intercept(fake, fake.headers))
            .build()
            .start();
    transport =
        new GrpcClientTransport("127.0.0.1", server.getPort(), "conta-1", false, credentials);
    transport.open();
    return transport;
  }

  @Test
  void semContaNenhumCabecalhoEComportamentoDeSempre() throws Exception {
    FakeService fake = new FakeService();
    GrpcClientTransport t = start(fake, AccountCredentials.none());

    t.listMatches();
    t.join("m1", "Ana");

    assertEquals(2, fake.headers.values.size());
    fake.headers.values.forEach(v -> assertNull(v));
    assertFalse(t.accessKeepaliveRunning(), "sem conta, sem keepalive");
  }

  @Test
  void comContaMssTodaChamadaLevaBearer() throws Exception {
    FakeService fake = new FakeService();
    FakeCredentials credentials = new FakeCredentials();
    GrpcClientTransport t = start(fake, credentials);

    t.listMatches();
    t.createMatch("Mesa", GameMode.FREE_FOR_ALL, 2, 30, 60);
    t.join("m1", "Ana");

    assertEquals(
        List.of("Bearer access-0", "Bearer access-0", "Bearer access-0"), fake.headers.values);
    assertEquals("conta-1", fake.lastJoinGuestId);
  }

  @Test
  void acessoRecusadoRenovaETentaUmaVez() throws Exception {
    FakeService fake = new FakeService();
    fake.rejectAccess.set(1);
    FakeCredentials credentials = new FakeCredentials();
    GrpcClientTransport t = start(fake, credentials);

    t.listMatches();

    assertEquals(List.of("Bearer access-0", "Bearer access-1"), fake.headers.values);
    assertEquals(1, credentials.renewals.get());
  }

  @Test
  void acessoRecusadoDeNovoNaoEntraEmLacoEPedeParaEntrar() throws Exception {
    FakeService fake = new FakeService();
    fake.rejectAccess.set(10);
    GrpcClientTransport t = start(fake, new FakeCredentials());

    AccountRefusedException e = assertThrows(AccountRefusedException.class, t::listMatches);

    assertEquals(Reason.ACCESS_REJECTED, e.reason());
    assertEquals(2, fake.headers.values.size(), "uma tentativa + uma renovada, nada mais");
  }

  @Test
  void entrarComAcessoRecusadoRenovaUmaVezNoStream() throws Exception {
    FakeService fake = new FakeService();
    fake.rejectAccess.set(1);
    GrpcClientTransport t = start(fake, new FakeCredentials());

    assertEquals("tok-1", t.join("m1", "Ana").token());

    assertEquals(List.of("Bearer access-0", "Bearer access-1"), fake.headers.values);
  }

  @Test
  void respostasDoContratoViramRecusasPorCaso() throws Exception {
    FakeService fake = new FakeService();
    GrpcClientTransport t = start(fake, new FakeCredentials());

    fake.createFailure =
        Status.UNAUTHENTICATED.withDescription("conta MSS necessária neste servidor");
    assertEquals(Reason.SIGN_IN_REQUIRED, createFails(t).reason());

    fake.createFailure =
        Status.PERMISSION_DENIED.withDescription("conta restrita: confirme o contato na conta MSS");
    assertEquals(Reason.ACCOUNT_RESTRICTED, createFails(t).reason());

    fake.createFailure = Status.UNAVAILABLE.withDescription("identidade MSS indisponível");
    assertEquals(Reason.IDENTITY_UNAVAILABLE_ON_SERVER, createFails(t).reason());

    fake.createFailure =
        Status.FAILED_PRECONDITION.withDescription("esta conta já ocupa um lugar nesta partida");
    assertEquals(Reason.ALREADY_SEATED, createFails(t).reason());
  }

  @Test
  void contaRestritaNoServidorAtualizaOEstadoGuardado() throws Exception {
    FakeService fake = new FakeService();
    FakeCredentials credentials = new FakeCredentials();
    GrpcClientTransport t = start(fake, credentials);
    fake.createFailure =
        Status.PERMISSION_DENIED.withDescription("conta restrita: confirme o contato na conta MSS");

    createFails(t);

    assertEquals(1, credentials.restricted.get());
  }

  @Test
  void assentoDeOutraContaNaReconexao() throws Exception {
    FakeService fake = new FakeService();
    fake.reconnectFailure =
        Status.PERMISSION_DENIED.withDescription("assento pertence a outra conta");
    GrpcClientTransport t = start(fake, new FakeCredentials());

    AccountRefusedException e =
        assertThrows(AccountRefusedException.class, () -> t.reconnect("tok-velho"));

    assertEquals(Reason.SEAT_OF_OTHER_ACCOUNT, e.reason());
  }

  @Test
  void falhaLocalNaIdentidadeNaoChegaAoServidor() throws Exception {
    FakeService fake = new FakeService();
    FakeCredentials credentials = new FakeCredentials();
    credentials.failure =
        new CredentialException(
            CredentialException.Reason.UNAVAILABLE, "Serviço de identidade MSS indisponível: x");
    GrpcClientTransport t = start(fake, credentials);

    AccountRefusedException e = assertThrows(AccountRefusedException.class, t::listMatches);

    assertEquals(Reason.LOCAL_IDENTITY_UNAVAILABLE, e.reason());
    assertEquals("Serviço de identidade MSS indisponível: x", e.getMessage());
    assertTrue(fake.headers.values.isEmpty(), "a chamada não saiu do cliente");
    assertEquals(0, credentials.renewals.get(), "falha local não gera nova tentativa");
  }

  @Test
  void errosQueNaoSaoDeContaMantemAMensagemDeSempre() throws Exception {
    FakeService fake = new FakeService();
    GrpcClientTransport t = start(fake, new FakeCredentials());
    fake.createFailure = Status.FAILED_PRECONDITION.withDescription("nome inválido");

    TransportException e =
        assertThrows(
            TransportException.class,
            () -> t.createMatch("Mesa", GameMode.FREE_FOR_ALL, 2, 30, 60));

    assertFalse(e instanceof AccountRefusedException);
    assertEquals("falha ao criar a partida", e.getMessage());
  }

  @Test
  void keepaliveRenovaOAcessoDuranteAPartida() throws Exception {
    FakeService fake = new FakeService();
    GrpcClientTransport t = start(fake, new FakeCredentials());

    t.join("m1", "Ana");
    assertTrue(t.accessKeepaliveRunning());
    t.refreshStreamAccess();

    assertEquals(1, fake.statsCalls.get());
    assertEquals("Bearer access-0", fake.headers.values.get(fake.headers.values.size() - 1));
    assertEquals("conta-1", fake.lastStatsGuestId);

    t.leaveMatch();
    assertFalse(t.accessKeepaliveRunning());
  }

  @Test
  void streamRecusandoOAcessoReabreComAcessoRenovado() throws Exception {
    FakeService fake = new FakeService();
    FakeCredentials credentials = new FakeCredentials();
    GrpcClientTransport t = start(fake, credentials);
    t.join("m1", "Ana");

    fake.openStream.onError(Status.UNAUTHENTICATED.withDescription(EXPIRED).asRuntimeException());

    long deadline = System.currentTimeMillis() + 5_000;
    while (fake.reconnectCalls.get() == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(1, fake.reconnectCalls.get());
    assertEquals("tok-1", fake.lastReconnectToken);
    assertEquals("Bearer access-1", fake.headers.values.get(fake.headers.values.size() - 1));
    assertEquals(1, credentials.renewals.get());
  }

  @Test
  void contaNuncaVaiEmTextoPuroForaDeLocalhost() {
    GrpcClientTransport remote =
        new GrpcClientTransport("10.0.0.9", 1099, "conta-1", false, new FakeCredentials());

    TransportException e = assertThrows(TransportException.class, remote::open);

    assertTrue(e.getMessage().contains("TLS"));
  }

  private static AccountRefusedException createFails(GrpcClientTransport t) {
    TransportException e =
        assertThrows(
            TransportException.class,
            () -> t.createMatch("Mesa", GameMode.FREE_FOR_ALL, 2, 30, 60));
    return assertInstanceOf(AccountRefusedException.class, e);
  }

  // ---------------------------------------------------------------- dublês

  /** Credencial da identidade de mentira: "access-N", N sobe a cada renovação. */
  private static final class FakeCredentials implements AccountCredentials {
    final AtomicInteger renewals = new AtomicInteger();
    final AtomicInteger restricted = new AtomicInteger();
    volatile CredentialException failure;

    @Override
    public String token() {
      if (failure != null) {
        throw failure;
      }
      return "access-" + renewals.get();
    }

    @Override
    public boolean renewAfterRejection() {
      renewals.incrementAndGet();
      return true;
    }

    @Override
    public Source source() {
      return Source.MSS_IDENTITY;
    }

    @Override
    public void accountRestricted() {
      restricted.incrementAndGet();
    }
  }

  /** Guarda o {@code authorization} de cada chamada recebida ({@code null} = sem cabeçalho). */
  private static final class HeaderCapture implements ServerInterceptor {
    final List<String> values = new CopyOnWriteArrayList<>();

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      values.add(headers.get(GameCallCredentials.AUTHORIZATION));
      return next.startCall(call, headers);
    }
  }

  /** {@code DominoHostImplBase} de mentira, com recusas de conta configuráveis. */
  private static final class FakeService extends DominoHostGrpc.DominoHostImplBase {
    final HeaderCapture headers = new HeaderCapture();
    final AtomicInteger rejectAccess = new AtomicInteger();
    final AtomicInteger reconnectCalls = new AtomicInteger();
    final AtomicInteger statsCalls = new AtomicInteger();
    volatile Status createFailure;
    volatile Status reconnectFailure;
    volatile String lastJoinGuestId;
    volatile String lastStatsGuestId;
    volatile String lastReconnectToken;
    volatile StreamObserver<JoinMessage> openStream;

    private boolean rejected(StreamObserver<?> response) {
      if (rejectAccess.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
        response.onError(Status.UNAUTHENTICATED.withDescription(EXPIRED).asRuntimeException());
        return true;
      }
      return false;
    }

    @Override
    public void listMatches(
        ListMatchesRequest request, StreamObserver<ListMatchesResponse> response) {
      if (rejected(response)) {
        return;
      }
      response.onNext(ListMatchesResponse.getDefaultInstance());
      response.onCompleted();
    }

    @Override
    public void createMatch(
        CreateMatchRequest request, StreamObserver<CreateMatchResponse> response) {
      if (rejected(response)) {
        return;
      }
      if (createFailure != null) {
        response.onError(createFailure.asRuntimeException());
        return;
      }
      response.onNext(CreateMatchResponse.newBuilder().setMatchId("m1").build());
      response.onCompleted();
    }

    @Override
    public void join(JoinRequest request, StreamObserver<JoinMessage> response) {
      if (rejected(response)) {
        return;
      }
      lastJoinGuestId = request.getGuestId();
      openStream = response;
      response.onNext(JoinMessage.newBuilder().setSession(session("tok-1")).build());
    }

    @Override
    public void reconnect(ReconnectRequest request, StreamObserver<JoinMessage> response) {
      reconnectCalls.incrementAndGet();
      lastReconnectToken = request.getToken();
      if (reconnectFailure != null) {
        response.onError(reconnectFailure.asRuntimeException());
        return;
      }
      openStream = response;
      response.onNext(JoinMessage.newBuilder().setSession(session(request.getToken())).build());
    }

    @Override
    public void getMyStats(GetMyStatsRequest request, StreamObserver<PlayerStats> response) {
      statsCalls.incrementAndGet();
      lastStatsGuestId = request.getGuestId();
      response.onNext(PlayerStats.getDefaultInstance());
      response.onCompleted();
    }

    private static Session session(String token) {
      return Session.newBuilder()
          .setToken(token)
          .setSeat(0)
          .setSnapshot(
              Snapshot.newBuilder()
                  .setMatchId("m1")
                  .setMode(br.com.mss.domino.net.grpc.proto.GameMode.FREE_FOR_ALL)
                  .build())
          .build();
    }
  }
}
