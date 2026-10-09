package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.app.IdentityAccountGateway.Purpose;
import br.com.mss.domino.app.IdentitySessionStore.StoredIdentitySession;
import br.com.mss.domino.net.config.IdentityTarget;
import br.com.mss.identity.client.IdentityClient;
import br.com.mss.identity.client.internal.v1.AccountSession;
import br.com.mss.identity.client.internal.v1.AccountState;
import br.com.mss.identity.client.internal.v1.ChallengeInfo;
import br.com.mss.identity.client.internal.v1.GetCapabilitiesRequest;
import br.com.mss.identity.client.internal.v1.GetCapabilitiesResponse;
import br.com.mss.identity.client.internal.v1.GetProfileRequest;
import br.com.mss.identity.client.internal.v1.GetProfileResponse;
import br.com.mss.identity.client.internal.v1.IdentityServiceGrpc;
import br.com.mss.identity.client.internal.v1.IssueGameAccessRequest;
import br.com.mss.identity.client.internal.v1.IssueGameAccessResponse;
import br.com.mss.identity.client.internal.v1.RecoverAccountRequest;
import br.com.mss.identity.client.internal.v1.RecoverAccountResponse;
import br.com.mss.identity.client.internal.v1.RefreshSessionRequest;
import br.com.mss.identity.client.internal.v1.RefreshSessionResponse;
import br.com.mss.identity.client.internal.v1.RequestChallengeRequest;
import br.com.mss.identity.client.internal.v1.RequestChallengeResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Adaptador sobre o {@code identity-client-java}, contra um serviço falso em processo. */
class IdentityClientGatewayTest {

  /** Store em memória da porta do desktop. */
  private static final class MemoryStore implements IdentitySessionStore {
    StoredIdentitySession saved;

    @Override
    public Optional<StoredIdentitySession> load() {
      return Optional.ofNullable(saved);
    }

    @Override
    public void save(StoredIdentitySession session) {
      saved = session;
    }

    @Override
    public void clear() {
      saved = null;
      sinceAccount = null;
      since = null;
    }

    String sinceAccount;
    Instant since;

    @Override
    public Optional<Instant> provisionalSince(String accountId) {
      return accountId.equals(sinceAccount) ? Optional.of(since) : Optional.empty();
    }

    @Override
    public void rememberProvisionalSince(String accountId, Instant at) {
      sinceAccount = accountId;
      since = at;
    }
  }

  /** Relógio ajustável para a carência. */
  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-06T12:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private volatile boolean contactVerified;
  private volatile AccountState refreshedState = AccountState.ACCOUNT_STATE_PROVISIONAL;
  private final AtomicInteger refreshes = new AtomicInteger();
  private final AtomicInteger profileCalls = new AtomicInteger();
  private final MutableClock clock = new MutableClock();

  /** Gateway com o store e o relógio controlados (estado derivado, BUG-003). */
  private IdentityClientGateway stateGateway() {
    return new IdentityClientGateway(
        () ->
            IdentityClient.builder()
                .channel(channel)
                .sessionStore(new IdentityClientGateway.StoreAdapter(store, clock))
                .build(),
        store,
        clock);
  }

  private void signedInAs(String state) {
    store.saved =
        new StoredIdentitySession(
            "sess-1", "acc-1", Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond(), state);
  }

  @Test
  void contaAtivaNaoConsultaAIdentidade() {
    signedInAs("ACTIVE");
    try (var g = stateGateway()) {
      assertEquals(IdentityAccountGateway.AccountState.ACTIVE, g.refreshStatus().state());
    }
    assertEquals(0, profileCalls.get());
    assertEquals(0, refreshes.get());
  }

  @Test
  void contatoConfirmadoEmOutroLugarViraAtivaSemRotacionar() {
    signedInAs("PROVISIONAL");
    contactVerified = true;
    try (var g = stateGateway()) {
      assertEquals(IdentityAccountGateway.AccountState.ACTIVE, g.refreshStatus().state());
    }
    assertEquals("ACTIVE", store.saved.state());
    assertEquals("sess-1", store.saved.sessionToken(), "mesma sessão");
    assertEquals(0, refreshes.get());
  }

  @Test
  void provisoriaViraRestritaDepoisDaCarencia() {
    signedInAs("PROVISIONAL");
    store.rememberProvisionalSince("acc-1", clock.now);
    try (var g = stateGateway()) {
      clock.now = clock.now.plus(Duration.ofMinutes(59));
      assertEquals(IdentityAccountGateway.AccountState.PROVISIONAL, g.refreshStatus().state());
      clock.now = clock.now.plus(Duration.ofMinutes(2));
      assertEquals(IdentityAccountGateway.AccountState.RESTRICTED, g.refreshStatus().state());
    }
    assertEquals("RESTRICTED", store.saved.state());
    assertEquals(0, refreshes.get());
  }

  @Test
  void semReferenciaDaCarenciaPerguntaAIdentidadeUmaVez() {
    signedInAs("PROVISIONAL");
    refreshedState = AccountState.ACCOUNT_STATE_PROVISIONAL;
    try (var g = stateGateway()) {
      assertEquals(IdentityAccountGateway.AccountState.PROVISIONAL, g.refreshStatus().state());
      assertEquals(1, refreshes.get());
      assertEquals(clock.now, store.since, "referência gravada na 1ª resposta");
      g.refreshStatus();
    }
    assertEquals(1, refreshes.get(), "a 2ª consulta deriva sem rotacionar");
  }

  @Test
  void cadastroProvisorioGravaAReferenciaDaCarencia() {
    new IdentityClientGateway.StoreAdapter(store, clock)
        .save(
            new br.com.mss.identity.client.Session(
                "sess-1",
                "acc-1",
                clock.now.plusSeconds(3600),
                br.com.mss.identity.client.AccountState.PROVISIONAL));
    Instant first = store.since;
    clock.now = clock.now.plusSeconds(600);
    new IdentityClientGateway.StoreAdapter(store, clock)
        .save(
            new br.com.mss.identity.client.Session(
                "sess-2",
                "acc-1",
                clock.now.plusSeconds(3600),
                br.com.mss.identity.client.AccountState.PROVISIONAL));

    assertEquals(Instant.parse("2026-10-06T12:00:00Z"), first);
    assertEquals(first, store.since, "a rotação não adia a carência");
  }

  @Test
  void recusaDoJogoMarcaAContaComoRestrita() {
    signedInAs("PROVISIONAL");
    try (var g = stateGateway()) {
      g.markRestricted();
      assertEquals(
          IdentityAccountGateway.AccountState.RESTRICTED, g.currentAccount().orElseThrow().state());
    }
    assertEquals("sess-1", store.saved.sessionToken());
  }

  private final AtomicInteger accessSerial = new AtomicInteger();
  private volatile Status accessFailure;
  private Server server;
  private ManagedChannel channel;
  private final MemoryStore store = new MemoryStore();
  private IdentityClientGateway gateway;

  @BeforeEach
  void start() throws Exception {
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(new FakeService())
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    gateway =
        new IdentityClientGateway(
            () ->
                IdentityClient.builder()
                    .channel(channel)
                    .sessionStore(new IdentityClientGateway.StoreAdapter(store))
                    .build());
  }

  @AfterEach
  void stop() {
    gateway.close();
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void capacidadesSoEmail() {
    var caps = gateway.capabilities();

    assertTrue(caps.email());
    assertFalse(caps.phone());
  }

  @Test
  void recuperarGuardaASessaoNoStoreDoDesktop() {
    var challenge = gateway.requestCode("ana@example.com", Purpose.RECOVER_ACCOUNT);
    var status = gateway.recover("ana@example.com", challenge, "123456");

    assertEquals("acc-1", status.accountId());
    assertEquals(IdentityAccountGateway.AccountState.ACTIVE, status.state());
    assertEquals("sess-1", store.saved.sessionToken());
    assertEquals("ACTIVE", store.saved.state());
    assertEquals(status, gateway.currentAccount().orElseThrow());
  }

  @Test
  void acessoDeJogoFicaEmCacheEInvalidarForcaUmNovo() {
    signedIn();

    String first = gateway.gameAccessToken();
    assertEquals(first, gateway.gameAccessToken());

    gateway.invalidateGameAccess();
    String second = gateway.gameAccessToken();

    assertNotEquals(first, second);
    assertEquals("sess-1", store.saved.sessionToken(), "sessão preservada");
  }

  @Test
  void semSessaoPedeParaEntrar() {
    var e = assertThrows(IdentityAccountException.class, () -> gateway.gameAccessToken());

    assertEquals(IdentityAccountException.Kind.NOT_SIGNED_IN, e.kind());
    assertTrue(e.requiresSignIn());
  }

  @Test
  void contaRestritaViraPermissionDeniedEmPortugues() {
    signedIn();
    accessFailure = Status.PERMISSION_DENIED.withDescription("restricted");

    var e = assertThrows(IdentityAccountException.class, () -> gateway.gameAccessToken());

    assertEquals(IdentityAccountException.Kind.PERMISSION_DENIED, e.kind());
    assertTrue(e.getMessage().contains("restrita"));
  }

  @Test
  void identidadeForaDoArViraUnavailable() {
    signedIn();
    accessFailure = Status.UNAVAILABLE;

    var e = assertThrows(IdentityAccountException.class, () -> gateway.gameAccessToken());

    assertEquals(IdentityAccountException.Kind.UNAVAILABLE, e.kind());
  }

  @Test
  void destinoRemotoEmTextoClaroERecusado() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IdentityClientGateway(
                new IdentityTarget("10.0.0.9", 9100, false), new MemoryStore(), "device-1"));
  }

  @Test
  void sessaoCorrompidaNoStoreEIgnorada() {
    store.saved = new StoredIdentitySession("t", "acc", 1L, "DESCONHECIDO");

    assertTrue(gateway.currentAccount().isEmpty());
  }

  private void signedIn() {
    store.saved =
        new StoredIdentitySession(
            "sess-1",
            "acc-1",
            Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond(),
            "ACTIVE");
  }

  private final class FakeService extends IdentityServiceGrpc.IdentityServiceImplBase {
    @Override
    public void getCapabilities(
        GetCapabilitiesRequest request, StreamObserver<GetCapabilitiesResponse> response) {
      response.onNext(GetCapabilitiesResponse.newBuilder().setEmailEnabled(true).build());
      response.onCompleted();
    }

    @Override
    public void requestChallenge(
        RequestChallengeRequest request, StreamObserver<RequestChallengeResponse> response) {
      response.onNext(
          RequestChallengeResponse.newBuilder()
              .setChallenge(
                  ChallengeInfo.newBuilder()
                      .setChallengeId("ch-1")
                      .setPurpose(request.getPurpose())
                      .setExpiresAtEpochSeconds(Instant.now().plusSeconds(600).getEpochSecond()))
              .build());
      response.onCompleted();
    }

    @Override
    public void recoverAccount(
        RecoverAccountRequest request, StreamObserver<RecoverAccountResponse> response) {
      if (!"123456".equals(request.getCode()) || !"ch-1".equals(request.getChallengeId())) {
        response.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        return;
      }
      response.onNext(
          RecoverAccountResponse.newBuilder()
              .setSession(
                  AccountSession.newBuilder()
                      .setSessionToken("sess-1")
                      .setAccountId("acc-1")
                      .setExpiresAtEpochSeconds(
                          Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond())
                      .setState(AccountState.ACCOUNT_STATE_ACTIVE))
              .build());
      response.onCompleted();
    }

    @Override
    public void getProfile(GetProfileRequest request, StreamObserver<GetProfileResponse> response) {
      profileCalls.incrementAndGet();
      response.onNext(
          GetProfileResponse.newBuilder()
              .setProfile(
                  br.com.mss.identity.client.internal.v1.Profile.newBuilder()
                      .setAccountId("acc-1")
                      .setNick("ana")
                      .setMaskedContact("a***@***.com")
                      .setContactVerified(contactVerified))
              .build());
      response.onCompleted();
    }

    @Override
    public void refreshSession(
        RefreshSessionRequest request, StreamObserver<RefreshSessionResponse> response) {
      int n = refreshes.incrementAndGet();
      response.onNext(
          RefreshSessionResponse.newBuilder()
              .setSession(
                  AccountSession.newBuilder()
                      .setSessionToken("sess-r" + n)
                      .setAccountId("acc-1")
                      .setExpiresAtEpochSeconds(
                          Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond())
                      .setState(refreshedState))
              .build());
      response.onCompleted();
    }

    @Override
    public void issueGameAccess(
        IssueGameAccessRequest request, StreamObserver<IssueGameAccessResponse> response) {
      if (accessFailure != null) {
        response.onError(accessFailure.asRuntimeException());
        return;
      }
      response.onNext(
          IssueGameAccessResponse.newBuilder()
              .setAccessToken("access-" + accessSerial.incrementAndGet())
              .setAudience(request.getAudience())
              .setExpiresAtEpochSeconds(Instant.now().plusSeconds(600).getEpochSecond())
              .build());
      response.onCompleted();
    }
  }
}
