package br.com.mss.domino.net.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preset oficial com identidade (Domino:M6-06, M1) e {@code identity} no {@code servers.json}. */
class IdentityPresetTest {

  @TempDir private Path tempDir;

  @AfterEach
  void limpaAPropriedadeDeSistema() {
    System.clearProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY);
  }

  private static ServerPreset official() {
    return ServerDirectory.loadBundled().presets().stream()
        .filter(ServerPreset::official)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void embutidoTrazOOficialComTlsEIdentidadeDeProducao() {
    ServerPreset official = official();

    assertEquals("Oficial", official.name());
    assertEquals("domino.minashonsoftware.com.br", official.host());
    assertEquals(443, official.port());
    assertTrue(official.tls());
    assertTrue(official.usesMssIdentity());
    assertEquals(
        new IdentityTarget("identity.minashonsoftware.com.br", 443, true), official.identity());
    assertTrue(ServerDirectory.isOfficialEndpoint("domino.minashonsoftware.com.br", 443, true));
    assertFalse(ServerDirectory.isOfficialEndpoint("domino.minashonsoftware.com.br", 443, false));
  }

  @Test
  void localContinuaPadraoSemContaNoEmbutido() {
    ServerPreset local = ServerDirectory.loadBundled().defaultPreset().orElseThrow();

    assertEquals("Local", local.name());
    assertFalse(local.usesMssIdentity());
    assertFalse(local.tls());
  }

  @Test
  void escolhaAntigaDoOficialSemIdentidadeViraOPresetNovo() {
    ServerPreset old =
        new ServerPreset("Oficial", "DOMINO.minashonsoftware.com.br", 443, true, true, true);

    ServerPreset upgraded = ServerDirectory.withOfficialIdentity(old);

    assertEquals(official().identity(), upgraded.identity());
    assertTrue(upgraded.official());
    assertTrue(upgraded.isDefault(), "mantém a marcação de padrão do registro antigo");
  }

  @Test
  void outrosPresetsNaoMudam() {
    ServerPreset lan = new ServerPreset("Casa", "192.168.0.5", 1099, true);
    ServerPreset semTls = new ServerPreset("x", "domino.minashonsoftware.com.br", 443, true);
    ServerPreset outraPorta =
        new ServerPreset("x", "domino.minashonsoftware.com.br", 8443, true, true, false);

    assertSame(lan, ServerDirectory.withOfficialIdentity(lan));
    assertSame(semTls, ServerDirectory.withOfficialIdentity(semTls));
    assertSame(outraPorta, ServerDirectory.withOfficialIdentity(outraPorta));
    assertNull(ServerDirectory.withOfficialIdentity(null));
  }

  @Test
  void arquivoExternoLeIdentidadeComTlsPorPadraoETextoPuroEmLocalhost() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(
        file,
        """
        [
          {"name": "Local (conta MSS)", "host": "localhost", "port": 1099, "default": true,
           "identity": "localhost:9100", "identityTls": false},
          {"name": "Staging", "host": "localhost", "port": 8443, "tls": true,
           "identity": "identity.staging.example:443"},
          {"name": "Local (sem conta)", "host": "localhost", "port": 1099}
        ]
        """);
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    List<ServerPreset> presets = ServerDirectory.load().presets();

    assertEquals(new IdentityTarget("localhost", 9100, false), presets.get(0).identity());
    assertEquals(
        new IdentityTarget("identity.staging.example", 443, true), presets.get(1).identity());
    assertFalse(presets.get(2).usesMssIdentity());
  }

  @Test
  void identidadeEmTextoPuroForaDeLocalhostInvalidaOArquivo() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(
        file,
        """
        [{"name": "LAN MSS", "host": "192.168.0.5", "port": 1099, "default": true,
          "identity": "192.168.0.9:9100", "identityTls": false}]
        """);
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    // cai para o embutido inteiro (com aviso no log), nunca manda conta em claro pela rede
    assertEquals(ServerDirectory.loadBundled().presets(), ServerDirectory.load().presets());
  }

  @Test
  void arquivoExternoApontandoParaOOficialSemIdentidadeGanhaAIdentidade() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(
        file,
        """
        [{"name": "Prod", "host": "domino.minashonsoftware.com.br", "port": 443, "tls": true,
          "default": true}]
        """);
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    ServerPreset preset = ServerDirectory.load().defaultPreset().orElseThrow();

    assertTrue(preset.usesMssIdentity());
  }

  @Test
  void destinoDeIdentidadeEValidado() {
    IdentityTarget target = IdentityTarget.parse(" localhost:9100 ", false);

    assertEquals("localhost:9100", target.authority());
    assertTrue(target.plaintextAllowed());
    assertFalse(new IdentityTarget("10.0.0.9", 9100, false).plaintextAllowed());
    assertTrue(new IdentityTarget("10.0.0.9", 9100, true).plaintextAllowed());
    assertTrue(IdentityTarget.isLoopbackHost("127.0.0.1"));
    assertTrue(IdentityTarget.isLoopbackHost("[::1]"));
    assertFalse(IdentityTarget.isLoopbackHost("localhost.example"));
    assertThrows(IllegalArgumentException.class, () -> IdentityTarget.parse("localhost", true));
    assertThrows(IllegalArgumentException.class, () -> IdentityTarget.parse("host:abc", true));
    assertThrows(IllegalArgumentException.class, () -> IdentityTarget.parse("host:0", true));
    assertThrows(IllegalArgumentException.class, () -> IdentityTarget.parse(null, true));
  }
}
