package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalSessionTokenStore} sobre um nó de {@link Preferences} isolado por teste (ADR-0019,
 * BUG-006, BUG-009).
 */
class LocalSessionTokenStoreTest {

  private Preferences node;
  private LocalSessionTokenStore store;
  private SessionTokenStore local;

  @BeforeEach
  void setUp() {
    node =
        Preferences.userRoot().node("br/com/mss/domino/test/session-tokens-" + UUID.randomUUID());
    store = new LocalSessionTokenStore(node);
    local = store.forServer("localhost", 1099);
  }

  @AfterEach
  void tearDown() throws BackingStoreException {
    node.removeNode();
  }

  @Test
  void semTokenSalvoDevolveVazio() {
    assertTrue(local.find("m1", 0).isEmpty());
  }

  @Test
  void salvaEAcha() {
    local.save("m1", 0, "tok-abc");

    assertEquals("tok-abc", local.find("m1", 0).orElseThrow());
  }

  @Test
  void assentosDiferentesNaMesmaPartidaSaoIndependentes() {
    local.save("m1", 0, "tok-seat0");
    local.save("m1", 1, "tok-seat1");

    assertEquals("tok-seat0", local.find("m1", 0).orElseThrow());
    assertEquals("tok-seat1", local.find("m1", 1).orElseThrow());
  }

  @Test
  void partidasDiferentesNoMesmoAssentoSaoIndependentes() {
    local.save("m1", 0, "tok-m1");
    local.save("m2", 0, "tok-m2");

    assertEquals("tok-m1", local.find("m1", 0).orElseThrow());
    assertEquals("tok-m2", local.find("m2", 0).orElseThrow());
  }

  @Test
  void salvarDeNovoSubstituiOToken() {
    local.save("m1", 0, "tok-velho");
    local.save("m1", 0, "tok-novo");

    assertEquals("tok-novo", local.find("m1", 0).orElseThrow());
  }

  @Test
  void tokenEmBrancoNaoEhSalvo() {
    local.save("m1", 0, "  ");

    assertTrue(local.find("m1", 0).isEmpty());
  }

  @Test
  void sobreviveAReabrirOStore() {
    local.save("m1", 0, "tok-abc");

    SessionTokenStore reopened = new LocalSessionTokenStore(node).forServer("localhost", 1099);

    assertEquals("tok-abc", reopened.find("m1", 0).orElseThrow());
  }

  /**
   * Repro do incidente de 18/09/2026 (BUG-006): dois clientes na mesma conta do Windows
   * compartilhavam o mesmo {@link Preferences}, sem nenhuma distinção de perfil -- "Retomar" num
   * cliente podia achar o token salvo pelo outro. {@link LocalSessionTokenStore#useProfile} escopa
   * cada leitura/gravação ao perfil ativo (ADR-0018): perfis diferentes não se enxergam.
   */
  @Test
  void perfisDiferentesNoMesmoNoDePreferencesNaoSeEnxergam() {
    store.useProfile(new PlayerId("perfil-rafael"));
    local.save("m2", 1, "tok-rafael");

    store.useProfile(new PlayerId("perfil-andressa"));
    local.save("m2", 0, "tok-andressa");

    // Andressa não vê o assento de Rafael...
    assertTrue(local.find("m2", 1).isEmpty());
    assertEquals("tok-andressa", local.find("m2", 0).orElseThrow());

    // ...e Rafael, de volta ao perfil dele, não vê o de Andressa.
    store.useProfile(new PlayerId("perfil-rafael"));
    assertTrue(local.find("m2", 0).isEmpty());
    assertEquals("tok-rafael", local.find("m2", 1).orElseThrow());
  }

  /**
   * Repro do BUG-009 (validação do M6-02, 28/09/2026): a mesma {@code m1} em dois servidores (ou no
   * mesmo host em outra porta) são partidas diferentes; o token de uma não pode aparecer na outra.
   */
  @Test
  void servidoresDiferentesNaoSeEnxergam() {
    SessionTokenStore casa = store.forServer("192.168.0.5", 1099);
    SessionTokenStore outraPorta = store.forServer("localhost", 1100);

    local.save("m1", 0, "tok-local");

    assertTrue(casa.find("m1", 0).isEmpty());
    assertTrue(outraPorta.find("m1", 0).isEmpty());
    assertEquals("tok-local", local.find("m1", 0).orElseThrow());
  }

  @Test
  void hostEhComparadoSemDiferenciarMaiusculas() {
    local.save("m1", 0, "tok-abc");

    assertEquals("tok-abc", store.forServer("LocalHost", 1099).find("m1", 0).orElseThrow());
  }

  @Test
  void removerDescartaSoAqueleAssento() {
    local.save("m1", 0, "tok-seat0");
    local.save("m1", 1, "tok-seat1");

    local.remove("m1", 0);

    assertTrue(local.find("m1", 0).isEmpty());
    assertEquals("tok-seat1", local.find("m1", 1).orElseThrow());
    local.remove("m9", 0); // inexistente: não falha
  }

  @Test
  void hostLongoDemaisParaNomeDeNoVaiPorHash() {
    String longHost = "a".repeat(120) + ".example";
    SessionTokenStore longo = store.forServer(longHost, 443);

    longo.save("m1", 0, "tok-longo");

    assertEquals("tok-longo", longo.find("m1", 0).orElseThrow());
    assertTrue(local.find("m1", 0).isEmpty());
    assertTrue(
        LocalSessionTokenStore.serverNodeName(longHost, 443).length()
            <= Preferences.MAX_NAME_LENGTH);
  }

  @Test
  void tokenGravadoNoFormatoAntigoSemServidorEhIgnorado() {
    // layout de antes do BUG-009: <perfil>/<matchId>|<seat>
    node.node("sem-perfil/m1|0").put("token", "tok-antigo");

    assertTrue(local.find("m1", 0).isEmpty());
  }
}
