package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.config.ServerPreset;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalServerChoiceStore} sobre um nó de {@link Preferences} isolado por teste (ADR-0022).
 */
class LocalServerChoiceStoreTest {

  private Preferences node;
  private LocalServerChoiceStore store;

  @BeforeEach
  void setUp() {
    node = Preferences.userRoot().node("br/com/mss/domino/test/serverChoice-" + UUID.randomUUID());
    store = new LocalServerChoiceStore(node);
  }

  @AfterEach
  void tearDown() throws BackingStoreException {
    node.removeNode();
  }

  @Test
  void semEscolhaNenhumaDevolveVazio() {
    assertTrue(store.lastChoice().isEmpty());
  }

  @Test
  void lembraAUltimaEscolha() {
    store.remember(new ServerPreset("Casa", "192.168.0.5", 6000, false));

    ServerPreset remembered = store.lastChoice().orElseThrow();

    assertEquals("Casa", remembered.name());
    assertEquals("192.168.0.5", remembered.host());
    assertEquals(6000, remembered.port());
  }

  @Test
  void escolherDeNovoSubstituiAAnterior() {
    store.remember(new ServerPreset("Casa", "192.168.0.5", 6000, false));
    store.remember(new ServerPreset("Local", "localhost", 1099, true));

    assertEquals("Local", store.lastChoice().orElseThrow().name());
  }

  @Test
  void lembraTls() {
    store.remember(new ServerPreset("Staging", "localhost", 8443, false, true, false));

    assertTrue(new LocalServerChoiceStore(node).lastChoice().orElseThrow().tls());
  }

  @Test
  void escolhaGravadaAntesDoM602VoltaComoTextoPuro() {
    node.put("name", "Casa");
    node.put("host", "192.168.0.5");
    node.putInt("port", 6000);

    assertFalse(store.lastChoice().orElseThrow().tls());
  }

  @Test
  void naoConfiaEmOficialVindoDoDispositivo() {
    store.remember(new ServerPreset("Falso oficial", "evil.example", 443, true, true, true));

    assertFalse(store.lastChoice().orElseThrow().official());
  }

  @Test
  void sobreviveAReabrirOStore() {
    store.remember(new ServerPreset("Casa", "192.168.0.5", 6000, false));

    LocalServerChoiceStore reopened = new LocalServerChoiceStore(node);

    assertEquals("Casa", reopened.lastChoice().orElseThrow().name());
  }
}
