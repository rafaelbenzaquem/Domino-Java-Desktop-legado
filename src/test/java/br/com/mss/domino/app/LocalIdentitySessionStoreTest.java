package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.app.IdentitySessionStore.StoredIdentitySession;
import br.com.mss.domino.net.config.IdentityTarget;
import br.com.mss.domino.net.config.ServerPreset;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalIdentitySessionStoreTest {

  private static final IdentityTarget LOCAL = new IdentityTarget("localhost", 9100, false);
  private static final IdentityTarget STAGING =
      new IdentityTarget("identity.staging.example", 443, true);

  private Preferences root;

  @BeforeEach
  void freshNode() {
    root = Preferences.userRoot().node("br/com/mss/domino/test/mssIdentity/" + System.nanoTime());
  }

  @AfterEach
  void wipeNode() throws BackingStoreException {
    root.removeNode();
  }

  @Test
  void semSessaoDevolveVazio() {
    assertTrue(new LocalIdentitySessionStore(root, LOCAL).load().isEmpty());
  }

  @Test
  void guardaERecuperaASessao() {
    var store = new LocalIdentitySessionStore(root, LOCAL);
    var session = new StoredIdentitySession("tok-1", "acc-1", 1_900_000_000L, "ACTIVE");

    store.save(session);

    assertEquals(session, new LocalIdentitySessionStore(root, LOCAL).load().orElseThrow());
  }

  @Test
  void referenciaDaCarenciaEPorContaESaiComASessao() {
    var store = new LocalIdentitySessionStore(root, LOCAL);
    store.save(new StoredIdentitySession("tok-1", "acc-1", 1_900_000_000L, "PROVISIONAL"));
    var at = java.time.Instant.ofEpochSecond(1_800_000_000L);

    store.rememberProvisionalSince("acc-1", at);

    var reopened = new LocalIdentitySessionStore(root, LOCAL);
    assertEquals(at, reopened.provisionalSince("acc-1").orElseThrow());
    assertTrue(reopened.provisionalSince("acc-2").isEmpty());
    reopened.clear();
    assertTrue(new LocalIdentitySessionStore(root, LOCAL).provisionalSince("acc-1").isEmpty());
  }

  @Test
  void sessaoDeUmDestinoNaoVazaParaOutro() {
    new LocalIdentitySessionStore(root, LOCAL)
        .save(new StoredIdentitySession("tok-local", "acc", 1L, "PROVISIONAL"));

    assertTrue(new LocalIdentitySessionStore(root, STAGING).load().isEmpty());
    assertTrue(
        new LocalIdentitySessionStore(root, new IdentityTarget("localhost", 9100, true))
            .load()
            .isEmpty());
  }

  @Test
  void clearApagaSoODestino() {
    var local = new LocalIdentitySessionStore(root, LOCAL);
    var staging = new LocalIdentitySessionStore(root, STAGING);
    local.save(new StoredIdentitySession("a", "acc", 1L, "ACTIVE"));
    staging.save(new StoredIdentitySession("b", "acc", 1L, "ACTIVE"));

    local.clear();
    local.clear(); // idempotente

    assertTrue(local.load().isEmpty());
    assertFalse(staging.load().isEmpty());
  }

  @Test
  void toStringNaoExpoeOToken() {
    var session = new StoredIdentitySession("segredo-123", "acc-1", 1L, "ACTIVE");

    assertFalse(session.toString().contains("segredo-123"));
  }

  @Test
  void nomeDoNoEValidoParaHostsLongos() {
    String name =
        LocalIdentitySessionStore.nodeName(
            new IdentityTarget("a".repeat(120) + ".example", 443, true));

    assertTrue(name.length() <= Preferences.MAX_NAME_LENGTH);
    assertFalse(name.contains("/"));
  }

  @Test
  void escolhaDeServidorLembraODestinoDeIdentidade() throws BackingStoreException {
    Preferences node = root.node("serverChoice");
    var choices = new LocalServerChoiceStore(node);

    choices.remember(new ServerPreset("Local MSS", "localhost", 1099, true, false, false, LOCAL));
    assertEquals(LOCAL, choices.lastChoice().orElseThrow().identity());

    choices.remember(new ServerPreset("LAN", "192.168.0.5", 1099, false, false, false));
    assertEquals(null, choices.lastChoice().orElseThrow().identity());
  }

  @Test
  void lembraONickSoParaAContaDaSessaoEApagaJuntoComEla() {
    var store = new LocalIdentitySessionStore(root, LOCAL);
    store.save(new StoredIdentitySession("tok", "acc-1", 1L, "ACTIVE"));

    store.rememberProfile("acc-1", " Ana ", null);

    assertEquals("Ana", store.nickFor("acc-1").orElseThrow());
    assertTrue(store.nickFor("acc-2").isEmpty());
    assertTrue(store.nickFor(null).isEmpty());
    store.clear();
    assertTrue(store.nickFor("acc-1").isEmpty());
    store.rememberProfile("acc-1", "  ", null);
    assertTrue(store.nickFor("acc-1").isEmpty());
  }
}
