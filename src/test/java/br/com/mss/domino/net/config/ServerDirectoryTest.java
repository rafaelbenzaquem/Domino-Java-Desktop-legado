package br.com.mss.domino.net.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ServerDirectory} — externo (prioridade total) vs. embutido (ADR-0022). */
class ServerDirectoryTest {

  @TempDir private Path tempDir;

  @AfterEach
  void limpaAPropriedadeDeSistema() {
    System.clearProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY);
  }

  @Test
  void semArquivoExternoUsaOEmbutidoComOLocal() {
    System.setProperty(
        ServerDirectory.EXTERNAL_FILE_PROPERTY, tempDir.resolve("nao-existe.json").toString());

    ServerPreset preset = ServerDirectory.load().defaultPreset().orElseThrow();

    assertEquals(new ServerPreset("Local", "localhost", 1099, true), preset);
  }

  @Test
  void arquivoExternoValidoSubstituiAListaInteira() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(
        file,
        """
        [
          {"name": "Casa", "host": "192.168.0.5", "port": 6000, "default": true}
        ]
        """);
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    ServerDirectory directory = ServerDirectory.load();

    assertEquals(1, directory.presets().size());
    assertEquals(
        new ServerPreset("Casa", "192.168.0.5", 6000, true),
        directory.defaultPreset().orElseThrow());
  }

  @Test
  void arquivoExternoMalformadoCaiParaOEmbutido() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(file, "isto nao e json valido {{{");
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    ServerPreset preset = ServerDirectory.load().defaultPreset().orElseThrow();

    assertEquals("Local", preset.name());
  }

  @Test
  void semNenhumPresetMarcadoDefaultUsaOPrimeiroDaLista() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(
        file,
        """
        [
          {"name": "A", "host": "10.0.0.1", "port": 1111, "default": false},
          {"name": "B", "host": "10.0.0.2", "port": 2222, "default": false}
        ]
        """);
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    ServerPreset preset = ServerDirectory.load().defaultPreset().orElseThrow();

    assertEquals("A", preset.name());
  }

  @Test
  void listaExternaVaziaNaoTemPresetPadrao() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(file, "[]");
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    ServerDirectory directory = ServerDirectory.load();

    assertTrue(directory.defaultPreset().isEmpty());
    assertEquals(List.of(), directory.presets());
  }

  @Test
  void presetSemTlsNemOfficialNoJsonEhTextoPuroENaoOficial() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(
        file,
        """
        [{"name": "Casa", "host": "192.168.0.5", "port": 6000, "default": true}]
        """);
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    ServerPreset preset = ServerDirectory.load().defaultPreset().orElseThrow();

    assertFalse(preset.tls());
    assertFalse(preset.official());
  }

  @Test
  void arquivoExternoLeTlsMasNuncaMarcaComoOficial() throws IOException {
    Path file = tempDir.resolve("servers.json");
    Files.writeString(
        file,
        """
        [{"name": "Staging", "host": "localhost", "port": 8443, "default": true,
          "tls": true, "official": true}]
        """);
    System.setProperty(ServerDirectory.EXTERNAL_FILE_PROPERTY, file.toString());

    ServerPreset preset = ServerDirectory.load().defaultPreset().orElseThrow();

    assertEquals(new ServerPreset("Staging", "localhost", 8443, true, true, false), preset);
  }

  @Test
  void presetDoEmbutidoSemTlsNaoEhOficialNemEndpointOficial() {
    // o embutido do M6-02 só tem o "Local" em texto puro; o preset oficial entra no M6-06
    assertFalse(ServerDirectory.isOfficialEndpoint("localhost", 1099, false));
    assertFalse(ServerDirectory.isOfficialEndpoint("localhost", 1099, true));
    assertFalse(ServerDirectory.loadBundled().presets().stream().anyMatch(ServerPreset::official));
  }

  @Test
  void oRecursoEmbutidoEmSiEstaPresenteEValido() {
    ServerDirectory bundled = ServerDirectory.loadBundled();

    assertEquals(
        new ServerPreset("Local", "localhost", 1099, true), bundled.defaultPreset().orElseThrow());
  }
}
