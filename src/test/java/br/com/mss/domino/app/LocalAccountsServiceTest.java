package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.app.IdentitySessionStore.StoredIdentitySession;
import br.com.mss.domino.app.LocalAccountsService.Entry;
import br.com.mss.domino.app.LocalAccountsService.Kind;
import br.com.mss.domino.app.LocalAccountsService.Usage;
import br.com.mss.domino.net.config.IdentityTarget;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "Gerenciar contas" (M1): só dados locais, nunca tokens, respeitando janelas abertas. */
class LocalAccountsServiceTest {

  private static final IdentityTarget LOCAL = new IdentityTarget("localhost", 9100, false);
  private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

  @TempDir Path dataDir;

  private Preferences appRoot;
  private final List<DataProfile> opened = new ArrayList<>();

  @BeforeEach
  void freshNode() {
    appRoot = Preferences.userRoot().node("br/com/mss/domino/test/accounts/" + System.nanoTime());
  }

  @AfterEach
  void cleanUp() throws BackingStoreException {
    opened.forEach(DataProfile::close);
    appRoot.removeNode();
  }

  private DataProfile open(String name) {
    DataProfile profile = DataProfile.acquire(dataDir, name, appRoot);
    opened.add(profile);
    return profile;
  }

  private LocalAccountsService service(DataProfile current) {
    return new LocalAccountsService(dataDir, current, appRoot, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static void signIn(DataProfile profile, String account, String nick, String state) {
    LocalIdentitySessionStore store = new LocalIdentitySessionStore(profile, LOCAL);
    store.save(
        new StoredIdentitySession(
            "segredo-" + account, account, NOW.plusSeconds(3600).getEpochSecond(), state));
    store.rememberProfile(account, nick, "a***@***.com");
  }

  @Test
  void listaContasMssDasJanelasSemTokens() {
    DataProfile padrao = open(null);
    DataProfile segunda = open(null);
    signIn(padrao, "conta-a", "Ana", "ACTIVE");
    signIn(segunda, "conta-b", "Bia", "PROVISIONAL");

    List<Entry> entries = service(padrao).list();

    Entry ana = entries.stream().filter(e -> "Ana".equals(e.name())).findFirst().orElseThrow();
    assertEquals(Kind.MSS, ana.kind());
    assertEquals(Usage.THIS_WINDOW, ana.usage());
    assertEquals("localhost:9100 (sem TLS)", ana.destination());
    assertEquals("a***@***.com", ana.account());
    assertTrue(ana.state().startsWith("ativa"), ana.state());
    Entry bia = entries.stream().filter(e -> "Bia".equals(e.name())).findFirst().orElseThrow();
    assertEquals(Usage.OTHER_WINDOW, bia.usage());
    assertTrue(bia.state().startsWith("provisória"), bia.state());
    assertFalse(entries.toString().contains("segredo-"), "nenhum token na listagem");
  }

  @Test
  void removerContaMssDestaJanelaApagaSoASessaoLocal() {
    DataProfile padrao = open(null);
    signIn(padrao, "conta-a", "Ana", "ACTIVE");
    LocalAccountsService service = service(padrao);
    Entry ana = service.list().stream().filter(e -> e.kind() == Kind.MSS).findFirst().orElseThrow();
    List<String> remote = new ArrayList<>();

    assertTrue(
        service.remove(ana, (target, store, deviceId) -> remote.add(target.authority())).isEmpty());

    assertEquals(List.of("localhost:9100"), remote);
    assertTrue(new LocalIdentitySessionStore(padrao, LOCAL).load().isEmpty());
  }

  @Test
  void falhaRemotaNaoImpedeARemocaoLocal() {
    DataProfile padrao = open(null);
    signIn(padrao, "conta-a", "Ana", "ACTIVE");
    LocalAccountsService service = service(padrao);
    Entry ana = service.list().stream().filter(e -> e.kind() == Kind.MSS).findFirst().orElseThrow();

    var warning =
        service.remove(
            ana,
            (target, store, deviceId) -> {
              throw new IllegalStateException("identidade fora do ar");
            });

    assertTrue(warning.orElseThrow().contains("não confirmou"));
    assertTrue(new LocalIdentitySessionStore(padrao, LOCAL).load().isEmpty());
  }

  @Test
  void naoRemoveNadaDeOutraJanelaAberta() {
    DataProfile padrao = open(null);
    DataProfile segunda = open(null);
    signIn(segunda, "conta-b", "Bia", "ACTIVE");
    LocalAccountsService service = service(padrao);
    Entry bia =
        service.list().stream().filter(e -> "Bia".equals(e.name())).findFirst().orElseThrow();

    assertTrue(service.removalBlocker(bia).orElseThrow().contains("outra janela"));
    assertThrows(DataProfileException.class, () -> service.remove(bia, null));
    assertFalse(new LocalIdentitySessionStore(segunda, LOCAL).load().isEmpty());
  }

  @Test
  void perfilLocalLivreERemovidoInteiroMasOPadraoNao() {
    DataProfile padrao = open(null);
    DataProfile livre = open("livre");
    signIn(livre, "conta-c", "Cid", "ACTIVE");
    livre.close();
    LocalAccountsService service = service(padrao);
    Entry perfil =
        service.list().stream()
            .filter(e -> e.kind() == Kind.DATA_PROFILE && "livre".equals(e.dataProfile()))
            .findFirst()
            .orElseThrow();
    Entry padraoEntry =
        service.list().stream()
            .filter(e -> e.kind() == Kind.DATA_PROFILE && e.dataProfile().equals("padrao"))
            .findFirst()
            .orElseThrow();

    assertTrue(service.removalBlocker(padraoEntry).isPresent());
    assertTrue(service.removalDescription(perfil).contains("inteiro"));
    service.remove(perfil, null);

    assertTrue(
        service.list().stream().noneMatch(e -> "livre".equals(e.dataProfile())),
        "perfil removido some da lista");
  }
}
