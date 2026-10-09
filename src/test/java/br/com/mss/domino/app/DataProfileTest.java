package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.app.IdentitySessionStore.StoredIdentitySession;
import br.com.mss.domino.net.config.IdentityTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Perfis locais de dados (M1): várias janelas no mesmo usuário do SO, cada uma com a sua sessão
 * MSS, os seus tokens de assento e os seus perfis de jogador, sem nunca compartilhar um perfil ao
 * mesmo tempo (lição do TchowStrick-Java-Desktop-Legado, onde a 2ª janela sobrescrevia a sessão da
 * 1ª).
 */
class DataProfileTest {

  private static final IdentityTarget LOCAL = new IdentityTarget("localhost", 9100, false);

  @TempDir Path dataDir;

  private Preferences appRoot;
  private final List<DataProfile> opened = new ArrayList<>();

  @BeforeEach
  void freshNode() {
    appRoot =
        Preferences.userRoot().node("br/com/mss/domino/test/dataProfile/" + System.nanoTime());
  }

  @AfterEach
  void cleanUp() throws BackingStoreException {
    opened.forEach(DataProfile::close);
    appRoot.removeNode();
  }

  private DataProfile open(String requested) {
    DataProfile profile = DataProfile.acquire(dataDir, requested, appRoot);
    opened.add(profile);
    return profile;
  }

  @Test
  void primeiraJanelaUsaOPadraoESeguintesOProximoLivre() {
    DataProfile first = open(null);
    DataProfile second = open(null);
    DataProfile third = open(null);

    assertEquals("padrao", first.name());
    assertEquals("padrão", first.displayName());
    assertTrue(first.isDefault());
    assertTrue(first.locked());
    assertEquals("perfil-2", second.name());
    assertEquals("perfil-3", third.name());
    assertFalse(second.isDefault());
  }

  @Test
  void perfilLiberadoVoltaASerUsado() {
    DataProfile first = open(null);
    first.close();

    assertEquals("padrao", open(null).name());
  }

  @Test
  void perfilExplicitoEmUsoERecusadoComMensagemClara() {
    open("teste");

    DataProfileException e = assertThrows(DataProfileException.class, () -> open("Teste"));

    assertTrue(e.getMessage().contains("já está aberto em outra janela"), e.getMessage());
    assertTrue(e.getMessage().contains("--perfil="), e.getMessage());
  }

  @Test
  void nomesSaoNormalizadosEValidados() {
    assertEquals("padrao", DataProfile.normalize("Padrão"));
    assertEquals("joao-2", DataProfile.normalize(" João-2 "));
    assertThrows(DataProfileException.class, () -> DataProfile.normalize("a/b"));
    assertThrows(DataProfileException.class, () -> DataProfile.normalize(""));
    assertThrows(DataProfileException.class, () -> DataProfile.normalize("perfis"));
    assertThrows(DataProfileException.class, () -> DataProfile.normalize("x".repeat(33)));
  }

  @Test
  void sessaoMssDeUmPerfilNaoApareceNoOutro() {
    DataProfile first = open(null);
    DataProfile second = open(null);
    new LocalIdentitySessionStore(first, LOCAL)
        .save(new StoredIdentitySession("tok-a", "conta-a", 1_900_000_000L, "ACTIVE"));

    assertTrue(new LocalIdentitySessionStore(second, LOCAL).load().isEmpty());

    new LocalIdentitySessionStore(second, LOCAL)
        .save(new StoredIdentitySession("tok-b", "conta-b", 1_900_000_000L, "ACTIVE"));

    assertEquals(
        "conta-a", new LocalIdentitySessionStore(first, LOCAL).load().orElseThrow().accountId());
    assertEquals(
        "conta-b", new LocalIdentitySessionStore(second, LOCAL).load().orElseThrow().accountId());
  }

  @Test
  void tokensDeAssentoEPerfisDeJogadorSaoIsolados() {
    DataProfile first = open(null);
    DataProfile second = open(null);
    LocalSessionTokenStore firstTokens = new LocalSessionTokenStore(first);
    firstTokens.useProfile(new PlayerId("p1"));
    firstTokens.forServer("localhost", 1099).save("m1", 0, "assento-0");
    PlayerProfile ana = new LocalProfileStore(first).create("Ana");

    LocalSessionTokenStore secondTokens = new LocalSessionTokenStore(second);
    secondTokens.useProfile(new PlayerId("p1"));
    assertTrue(secondTokens.forServer("localhost", 1099).find("m1", 0).isEmpty());
    assertTrue(new LocalProfileStore(second).list().isEmpty());
    assertEquals(ana, new LocalProfileStore(first).active().orElseThrow());
  }

  @Test
  void perfilPadraoLeOsDadosGravadosAntesDosPerfis() {
    // Layout anterior ao M1: nós-filhos direto no nó do pacote app.
    LocalSessionTokenStore old = new LocalSessionTokenStore(appRoot.node("session-tokens"));
    old.useProfile(new PlayerId("p1"));
    old.forServer("localhost", 1099).save("m1", 2, "assento-antigo");
    PlayerProfile antigo = new LocalProfileStore(appRoot.node("profiles")).create("Antigo");

    DataProfile padrao = open(null);

    LocalSessionTokenStore tokens = new LocalSessionTokenStore(padrao);
    tokens.useProfile(new PlayerId("p1"));
    assertEquals("assento-antigo", tokens.forServer("localhost", 1099).find("m1", 2).orElseThrow());
    assertEquals(antigo, new LocalProfileStore(padrao).active().orElseThrow());
    assertTrue(new LocalProfileStore(open(null)).list().isEmpty());
  }

  @Test
  void cadaPerfilEUmDispositivoProprioParaAIdentidade() {
    DataProfile first = open(null);
    DataProfile second = open(null);

    String deviceA = first.identityDeviceId();
    String deviceB = second.identityDeviceId();

    assertNotEquals(deviceA, deviceB);
    assertEquals(deviceA, first.identityDeviceId());
    assertEquals(deviceB, second.identityDeviceId());
  }

  @Test
  void semDiretorioDeDadosAbreOPadraoSemTrava() throws Exception {
    Path notADir = dataDir.resolve("arquivo");
    Files.writeString(notADir, "x");

    DataProfile profile = DataProfile.acquire(notADir, null, appRoot);
    opened.add(profile);

    assertEquals("padrao", profile.name());
    assertFalse(profile.locked());
  }

  @Test
  void diretorioDeDadosPadraoRespeitaAPropriedade() {
    System.setProperty(DataProfile.DATA_DIR_PROPERTY, dataDir.toString());
    try {
      assertEquals(dataDir, DataProfile.defaultDataDir());
    } finally {
      System.clearProperty(DataProfile.DATA_DIR_PROPERTY);
    }
    assertTrue(DataProfile.defaultDataDir().endsWith(".domino"));
  }

  @Test
  void janelasDemaisSaoRecusadas() {
    for (int i = 0; i < DataProfile.MAX_AUTO_PROFILES; i++) {
      open(null);
    }
    DataProfileException e = assertThrows(DataProfileException.class, () -> open(null));
    assertTrue(e.getMessage().contains("janelas"), e.getMessage());
  }

  @Test
  void travaEnxergadaPorOutraInstancia() {
    open("outra");

    assertTrue(DataProfile.lockedElsewhere(dataDir, "outra"));
    assertFalse(DataProfile.lockedElsewhere(dataDir, "livre"));
  }
}
