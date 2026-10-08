package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link LocalProfileStore} sobre um nó de {@link Preferences} isolado por teste (ADR-0018). */
class LocalProfileStoreTest {

  private Preferences node;
  private LocalProfileStore store;

  @BeforeEach
  void setUp() {
    node = Preferences.userRoot().node("br/com/mss/domino/test/profiles-" + UUID.randomUUID());
    store = new LocalProfileStore(node);
  }

  @AfterEach
  void tearDown() throws BackingStoreException {
    node.removeNode();
  }

  @Test
  void semPerfil_listaEAtivoVazios() {
    assertTrue(store.list().isEmpty());
    assertTrue(store.active().isEmpty());
  }

  @Test
  void create_apareceNaListaEViraOAtivo() {
    PlayerProfile ana = store.create("Ana");

    assertEquals(List.of(ana), store.list());
    assertEquals(ana, store.active().orElseThrow());
    assertEquals(ana, store.find(ana.id()).orElseThrow());
  }

  @Test
  void create_multiplosPerfis_mantemAOrdemDeCriacao() {
    PlayerProfile ana = store.create("Ana");
    PlayerProfile beto = store.create("Beto");
    PlayerProfile caio = store.create("Caio");

    assertEquals(List.of(ana, beto, caio), store.list());
    assertEquals(caio, store.active().orElseThrow(), "o último criado vira o ativo");
  }

  @Test
  void setActive_trocaOPerfilAtivo() {
    PlayerProfile ana = store.create("Ana");
    PlayerProfile beto = store.create("Beto"); // ativo agora é o Beto

    store.setActive(ana.id());

    assertEquals(ana, store.active().orElseThrow());
  }

  @Test
  void setActive_idDesconhecido_naoMudaOAtivo() {
    PlayerProfile ana = store.create("Ana");

    store.setActive(new PlayerId("nao-existe"));

    assertEquals(ana, store.active().orElseThrow());
  }

  @Test
  void find_idDesconhecido_ehVazio() {
    assertTrue(store.find(new PlayerId("nao-existe")).isEmpty());
  }
}
