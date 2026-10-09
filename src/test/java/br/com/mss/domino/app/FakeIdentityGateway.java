package br.com.mss.domino.app;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Fake em memória da porta da conta MSS, sem rede nem biblioteca. */
class FakeIdentityGateway implements IdentityAccountGateway {

  Capabilities capabilities = new Capabilities(true, false);
  final Set<String> existingEmails = new HashSet<>();
  final List<String> calls = new ArrayList<>();
  String validCode = "123456";
  AccountStatus session;
  AccountState stateAfterConfirm = AccountState.ACTIVE;
  IdentityAccountException nextAccessError;
  IdentityAccountException signOutError;
  int accessSerial;
  int challengeSerial;
  boolean closed;

  @Override
  public Capabilities capabilities() {
    calls.add("capabilities");
    return capabilities;
  }

  @Override
  public SignUp signUp(String nick, String email) {
    calls.add("signUp:" + nick + ":" + email);
    if (existingEmails.contains(email)) {
      return new SignUp(Optional.empty(), Optional.empty());
    }
    existingEmails.add(email);
    session = new AccountStatus("acc-" + email, AccountState.PROVISIONAL, future());
    return new SignUp(Optional.of(session), Optional.of(challenge(Purpose.VERIFY_CONTACT)));
  }

  @Override
  public Challenge requestCode(String email, Purpose purpose) {
    calls.add("requestCode:" + purpose);
    return challenge(purpose);
  }

  @Override
  public AccountStatus confirmContact(String email, Challenge challenge, String code) {
    calls.add("confirm:" + challenge.challengeId() + ":" + code);
    checkCode(challenge, Purpose.VERIFY_CONTACT, code);
    session = new AccountStatus("acc-" + email, stateAfterConfirm, future());
    return session;
  }

  @Override
  public AccountStatus recover(String email, Challenge challenge, String code) {
    calls.add("recover:" + challenge.challengeId() + ":" + code);
    checkCode(challenge, Purpose.RECOVER_ACCOUNT, code);
    session = new AccountStatus("acc-" + email, AccountState.ACTIVE, future());
    return session;
  }

  @Override
  public Optional<AccountStatus> currentAccount() {
    return Optional.ofNullable(session);
  }

  @Override
  public AccountStatus refreshStatus() {
    if (session == null) {
      throw error(IdentityAccountException.Kind.NOT_SIGNED_IN);
    }
    return session;
  }

  @Override
  public Profile profile() {
    return new Profile(session.accountId(), "nick", "", "a***@x", true);
  }

  @Override
  public Profile updateProfile(String nick, String avatarId) {
    calls.add("updateProfile:" + nick + ":" + avatarId);
    return new Profile(session.accountId(), nick, avatarId, "a***@x", true);
  }

  @Override
  public String gameAccessToken() {
    calls.add("gameAccess");
    if (nextAccessError != null) {
      throw nextAccessError;
    }
    return "access-" + accessSerial;
  }

  @Override
  public void markRestricted() {
    calls.add("markRestricted");
    if (session != null) {
      session =
          new AccountStatus(
              session.accountId(), AccountState.RESTRICTED, session.sessionExpiresAt());
    }
  }

  @Override
  public void invalidateGameAccess() {
    calls.add("invalidate");
    accessSerial++;
  }

  @Override
  public void signOut(boolean allDevices) {
    calls.add("signOut:" + allDevices);
    session = null;
    if (signOutError != null) {
      throw signOutError;
    }
  }

  @Override
  public void close() {
    closed = true;
  }

  static IdentityAccountException error(IdentityAccountException.Kind kind) {
    return new IdentityAccountException(kind, IdentityAccountException.defaultMessage(kind));
  }

  private Challenge challenge(Purpose purpose) {
    return new Challenge("ch-" + (++challengeSerial), purpose, future());
  }

  private void checkCode(Challenge challenge, Purpose expected, String code) {
    if (challenge.purpose() != expected) {
      throw error(IdentityAccountException.Kind.INVALID_ARGUMENT);
    }
    if (!validCode.equals(code)) {
      throw error(IdentityAccountException.Kind.INVALID_ARGUMENT);
    }
  }

  private static Instant future() {
    return Instant.now().plusSeconds(3600);
  }
}
