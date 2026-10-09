package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.config.IdentityTarget;
import br.com.mss.domino.net.config.ServerPreset;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Escolha de servidor e tokens de assento com a conta MSS (M1). */
class LocalStoresIdentityTest {

  private static final IdentityTarget LOCAL = new IdentityTarget("localhost", 9100, false);

  private Preferences node;

  @BeforeEach
  void setUp() {
    node = Preferences.userRoot().node("br/com/mss/domino/test/identity-" + UUID.randomUUID());
  }

  @AfterEach
  void tearDown() throws BackingStoreException {
    node.removeNode();
  }

  @Test
  void escolhaDeServidorLembraODestinoDeIdentidadeEApagaQuandoNaoTem() {
    LocalServerChoiceStore choices = new LocalServerChoiceStore(node.node("serverChoice"));

    choices.remember(
        new ServerPreset("Local (conta MSS)", "localhost", 1099, true, false, false, LOCAL));
    ServerPreset saved = choices.lastChoice().orElseThrow();
    assertEquals(LOCAL, saved.identity());
    assertTrue(saved.usesMssIdentity());

    choices.remember(new ServerPreset("LAN", "192.168.0.5", 1099, false));
    assertNull(choices.lastChoice().orElseThrow().identity());
  }

  @Test
  void identidadeGravadaEmTextoPuroForaDeLocalhostEDescartada() {
    Preferences raw = node.node("serverChoice");
    raw.put("host", "10.0.0.5");
    raw.putInt("port", 1099);
    raw.put("identityHost", "10.0.0.9");
    raw.putInt("identityPort", 9100);
    raw.putBoolean("identityTls", false);

    assertNull(new LocalServerChoiceStore(raw).lastChoice().orElseThrow().identity());
  }

  @Test
  void tokensDeAssentoSaoSeparadosPorContaMss() {
    LocalSessionTokenStore store = new LocalSessionTokenStore(node.node("session-tokens"));
    store.useProfile(new PlayerId("p1"));
    SessionTokenStore contaA = store.forServer("localhost", 1099, "conta-a");
    SessionTokenStore contaB = store.forServer("localhost", 1099, "conta-b");
    SessionTokenStore semConta = store.forServer("localhost", 1099);

    contaA.save("m1", 0, "assento-a");

    assertEquals("assento-a", contaA.find("m1", 0).orElseThrow());
    assertTrue(contaB.find("m1", 0).isEmpty(), "outra conta não vê o assento");
    assertTrue(semConta.find("m1", 0).isEmpty(), "servidor sem conta não vê o assento");
    assertTrue(
        store.forServer("localhost", 1099, " ").find("m1", 0).isEmpty(),
        "conta em branco = sem conta");
  }

  @Test
  void contaComIdLongoOuComBarraViraNomeDeNoValido() {
    LocalSessionTokenStore store = new LocalSessionTokenStore(node.node("session-tokens"));
    store.useProfile(new PlayerId("p1"));
    SessionTokenStore odd = store.forServer("localhost", 1099, "a/b" + "x".repeat(100));

    odd.save("m1", 1, "tok");

    assertEquals("tok", odd.find("m1", 1).orElseThrow());
  }
}
