package br.com.mss.domino.app;

import br.com.mss.domino.app.IdentitySessionStore.StoredIdentitySession;
import br.com.mss.domino.net.config.IdentityTarget;
import br.com.mss.identity.client.ContactInput;
import br.com.mss.identity.client.IdentityClient;
import br.com.mss.identity.client.IdentityException;
import br.com.mss.identity.client.NotSignedInException;
import br.com.mss.identity.client.Session;
import br.com.mss.identity.client.SessionStore;
import br.com.mss.identity.client.SignUpResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptador de {@link IdentityAccountGateway} sobre o {@code identity-client-java} (MSSIdentity
 * M3-01). Isola a biblioteca: a UI e os testes só veem a porta. Porta do adaptador do
 * TchowStrick-Java-Desktop-Legado, já com as correções do BUG-003 (estado consultado) e do BUG-004
 * de lá (o {@code deviceId} sempre vem do perfil local; não há construtor que grave fora dele).
 */
public final class IdentityClientGateway implements IdentityAccountGateway {

  private static final Logger LOG = LoggerFactory.getLogger(IdentityClientGateway.class);

  private final IdentityClient client;

  /** Sessão local, para guardar o estado derivado; {@code null} em testes simples. */
  private final IdentitySessionStore store;

  private final Clock clock;

  /**
   * Canal próprio para {@code target}, sessão em {@code store}, {@code deviceId} do perfil local de
   * dados ({@link DataProfile#identityDeviceId()}).
   *
   * @throws IllegalArgumentException identidade em texto claro fora de localhost.
   */
  public IdentityClientGateway(IdentityTarget target, IdentitySessionStore store, String deviceId) {
    this(factoryFor(target, store, deviceId), store, Clock.systemUTC());
  }

  private static Supplier<IdentityClient> factoryFor(
      IdentityTarget target, IdentitySessionStore store, String deviceId) {
    if (!target.plaintextAllowed()) {
      throw new IllegalArgumentException(
          "identidade sem TLS só é permitida em localhost: " + target.authority());
    }
    return () ->
        IdentityClient.builder()
            .target(target.authority())
            .plaintext(!target.tls())
            .sessionStore(new StoreAdapter(store))
            .deviceId(deviceId)
            .callTimeout(Duration.ofSeconds(10))
            .build();
  }

  /** Testes: fábrica arbitrária (ex.: canal em processo). */
  IdentityClientGateway(Supplier<IdentityClient> factory) {
    this(factory, null, Clock.systemUTC());
  }

  /** Testes: fábrica arbitrária com o store da sessão (o mesmo do adaptador) e relógio. */
  IdentityClientGateway(Supplier<IdentityClient> factory, IdentitySessionStore store, Clock clock) {
    this.client = factory.get();
    this.store = store;
    this.clock = clock;
  }

  @Override
  public Capabilities capabilities() {
    var c = run(client::capabilities);
    return new Capabilities(c.email(), c.phone());
  }

  @Override
  public SignUp signUp(String nick, String email) {
    SignUpResult result = run(() -> client.createAccount(nick, contact(email), null));
    return new SignUp(
        result.session().map(IdentityClientGateway::status),
        result.challenge().map(IdentityClientGateway::challenge));
  }

  @Override
  public Challenge requestCode(String email, Purpose purpose) {
    return challenge(run(() -> client.requestChallenge(contact(email), purpose.name())));
  }

  @Override
  public AccountStatus confirmContact(String email, Challenge challenge, String code) {
    return status(run(() -> client.confirmContact(contact(email), challenge.challengeId(), code)));
  }

  @Override
  public AccountStatus recover(String email, Challenge challenge, String code) {
    return status(run(() -> client.recoverAccount(contact(email), challenge.challengeId(), code)));
  }

  @Override
  public Optional<AccountStatus> currentAccount() {
    return client.currentSession().map(IdentityClientGateway::status);
  }

  @Override
  public AccountStatus refreshStatus() {
    Session session = run(client::refreshIfNeeded);
    AccountState stored = AccountState.valueOf(session.state().name());
    if (stored == AccountState.ACTIVE) {
      return status(session);
    }
    // O estado guardado só muda quando a sessão é rotacionada; o perfil diz na hora se o contato
    // já foi confirmado (inclusive em outro dispositivo).
    boolean verified = run(client::profile).contactVerified();
    Optional<Instant> since =
        store == null ? Optional.empty() : store.provisionalSince(session.accountId());
    Optional<AccountState> derived =
        IdentityAccountGateway.deriveState(stored, verified, since, clock.instant());
    if (derived.isEmpty()) {
      // Sem referência para a carência: uma rotação devolve o estado calculado pela identidade.
      return status(run(client::refresh));
    }
    return persist(session, derived.get());
  }

  @Override
  public void markRestricted() {
    client.currentSession().ifPresent(s -> persist(s, AccountState.RESTRICTED));
  }

  /** Guarda {@code state} na sessão local (mesmo token) e devolve o resumo. */
  private AccountStatus persist(Session session, AccountState state) {
    if (store != null && !state.name().equals(session.state().name())) {
      store.save(
          new StoredIdentitySession(
              session.sessionToken(),
              session.accountId(),
              session.expiresAt().getEpochSecond(),
              state.name()));
    }
    return new AccountStatus(session.accountId(), state, session.expiresAt());
  }

  @Override
  public Profile profile() {
    return profile(run(client::profile));
  }

  @Override
  public Profile updateProfile(String nick, String avatarId) {
    return profile(run(() -> client.updateProfile(nick, null, avatarId)));
  }

  @Override
  public String gameAccessToken() {
    return run(() -> client.issueGameAccess(GAME_AUDIENCE)).accessToken();
  }

  @Override
  public void invalidateGameAccess() {
    client.invalidateGameAccess(GAME_AUDIENCE);
  }

  @Override
  public void signOut(boolean allDevices) {
    run(
        () -> {
          client.signOut(allDevices);
          return null;
        });
  }

  @Override
  public void close() {
    client.close();
  }

  // ---------------------------------------------------------------- tradução

  private static <T> T run(Supplier<T> call) {
    try {
      return call.get();
    } catch (NotSignedInException e) {
      throw new IdentityAccountException(
          IdentityAccountException.Kind.NOT_SIGNED_IN,
          IdentityAccountException.defaultMessage(IdentityAccountException.Kind.NOT_SIGNED_IN),
          e);
    } catch (IdentityException e) {
      IdentityAccountException.Kind kind = kind(e.kind());
      // A mensagem da biblioteca nunca traz contato, token nem código.
      LOG.warn("identidade MSS recusou a operação: {}", e.getMessage());
      throw new IdentityAccountException(kind, IdentityAccountException.defaultMessage(kind), e);
    } catch (IllegalArgumentException e) {
      throw new IdentityAccountException(
          IdentityAccountException.Kind.INVALID_ARGUMENT,
          IdentityAccountException.defaultMessage(IdentityAccountException.Kind.INVALID_ARGUMENT),
          e);
    }
  }

  static IdentityAccountException.Kind kind(IdentityException.Kind kind) {
    return switch (kind) {
      case UNAUTHENTICATED -> IdentityAccountException.Kind.UNAUTHENTICATED;
      case PERMISSION_DENIED -> IdentityAccountException.Kind.PERMISSION_DENIED;
      case INVALID_ARGUMENT -> IdentityAccountException.Kind.INVALID_ARGUMENT;
      case FAILED_PRECONDITION -> IdentityAccountException.Kind.FAILED_PRECONDITION;
      case RESOURCE_EXHAUSTED -> IdentityAccountException.Kind.RESOURCE_EXHAUSTED;
      case UNAVAILABLE -> IdentityAccountException.Kind.UNAVAILABLE;
      case INTERNAL -> IdentityAccountException.Kind.INTERNAL;
    };
  }

  private static ContactInput contact(String email) {
    return ContactInput.email(email);
  }

  private static AccountStatus status(Session s) {
    return new AccountStatus(s.accountId(), AccountState.valueOf(s.state().name()), s.expiresAt());
  }

  private static Challenge challenge(br.com.mss.identity.client.Challenge c) {
    Purpose purpose =
        Purpose.RECOVER_ACCOUNT.name().equals(c.purpose())
            ? Purpose.RECOVER_ACCOUNT
            : Purpose.VERIFY_CONTACT;
    return new Challenge(c.challengeId(), purpose, c.expiresAt());
  }

  private static Profile profile(br.com.mss.identity.client.Profile p) {
    return new Profile(
        p.accountId(), p.nick(), p.avatarId(), p.maskedContact(), p.contactVerified());
  }

  /** Expõe o {@link IdentitySessionStore} do desktop como o store da biblioteca. */
  static final class StoreAdapter implements SessionStore {
    private final IdentitySessionStore store;
    private final Clock clock;

    StoreAdapter(IdentitySessionStore store) {
      this(store, Clock.systemUTC());
    }

    StoreAdapter(IdentitySessionStore store, Clock clock) {
      this.store = store;
      this.clock = clock;
    }

    @Override
    public Optional<Session> load() {
      return store
          .load()
          .flatMap(
              s -> {
                try {
                  return Optional.of(
                      new Session(
                          s.sessionToken(),
                          s.accountId(),
                          Instant.ofEpochSecond(s.expiresAtEpochSeconds()),
                          br.com.mss.identity.client.AccountState.valueOf(s.state())));
                } catch (RuntimeException e) {
                  return Optional.empty(); // registro antigo/corrompido
                }
              });
    }

    @Override
    public void save(Session session) {
      store.save(
          new StoredIdentitySession(
              session.sessionToken(),
              session.accountId(),
              session.expiresAt().getEpochSecond(),
              session.state().name()));
      // Referência da carência: a 1ª vez que a identidade informou a conta provisória.
      if (session.state() == br.com.mss.identity.client.AccountState.PROVISIONAL
          && store.provisionalSince(session.accountId()).isEmpty()) {
        store.rememberProvisionalSince(session.accountId(), clock.instant());
      }
    }

    @Override
    public void clear() {
      store.clear();
    }
  }
}
