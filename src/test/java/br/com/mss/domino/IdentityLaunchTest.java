package br.com.mss.domino;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.app.ServerChoiceStore;
import br.com.mss.domino.net.config.IdentityTarget;
import br.com.mss.domino.net.config.ServerDirectory;
import br.com.mss.domino.net.config.ServerPreset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code --identity=}/{@code --identity-plaintext}/{@code --perfil=} e a escolha salva (M1). */
class IdentityLaunchTest {

  private static final ServerPreset CLI = Main.parseServerOption("localhost:1099");

  @Test
  void identidadeAcompanhaOServidorDaLinhaDeComando() {
    ServerPreset preset = Main.withIdentityOption(CLI, "localhost:9100", true);

    assertEquals(new IdentityTarget("localhost", 9100, false), preset.identity());
    assertEquals("localhost", preset.host());
    assertEquals(1099, preset.port());
    assertFalse(preset.tls(), "--server= continua em texto puro");
    assertFalse(preset.official());
  }

  @Test
  void identidadeUsaTlsPorPadrao() {
    assertTrue(Main.withIdentityOption(CLI, "identity.example:443", false).identity().tls());
  }

  @Test
  void semIdentidadeNadaMuda() {
    assertSame(CLI, Main.withIdentityOption(CLI, null, false));
    assertNull(Main.withIdentityOption(null, null, false));
  }

  @Test
  void usosInvalidosSaoRecusadosComMensagem() {
    var semServidor =
        assertThrows(
            IllegalArgumentException.class,
            () -> Main.withIdentityOption(null, "localhost:9100", true));
    assertTrue(semServidor.getMessage().contains("--server="));

    var remotoEmClaro =
        assertThrows(
            IllegalArgumentException.class,
            () -> Main.withIdentityOption(CLI, "10.0.0.9:9100", true));
    assertTrue(remotoEmClaro.getMessage().contains("só é aceito para localhost"));

    var plaintextSozinho =
        assertThrows(
            IllegalArgumentException.class, () -> Main.withIdentityOption(CLI, null, true));
    assertTrue(plaintextSozinho.getMessage().contains("--identity="));

    assertThrows(
        IllegalArgumentException.class, () -> Main.withIdentityOption(CLI, "semporta", false));
  }

  @Test
  void opcoesNovasNaoGeramAvisoDeDesconhecido() {
    assertDoesNotThrow(
        () ->
            Main.warnUnknownArgs(
                new String[] {
                  "--server=localhost:1099",
                  "--identity=localhost:9100",
                  "--identity-plaintext",
                  "--perfil=teste"
                }));
    assertEquals("teste", Main.stringOption(new String[] {"--perfil=teste"}, Main.OPTION_PERFIL));
    assertEquals(
        "localhost:9100",
        Main.stringOption(
            new String[] {"--identity-plaintext", "--identity=localhost:9100"},
            Main.OPTION_IDENTITY));
  }

  /** Escolha em memória que registra regravações. */
  private static final class MemoryChoice implements ServerChoiceStore {
    ServerPreset saved;
    final List<ServerPreset> writes = new ArrayList<>();

    @Override
    public Optional<ServerPreset> lastChoice() {
      return Optional.ofNullable(saved);
    }

    @Override
    public void remember(ServerPreset preset) {
      saved = preset;
      writes.add(preset);
    }
  }

  @Test
  void escolhaSalvaDoOficialSemIdentidadeEAtualizadaERegravada() {
    MemoryChoice choice = new MemoryChoice();
    choice.saved =
        new ServerPreset("Oficial", "domino.minashonsoftware.com.br", 443, true, true, true);

    ServerPreset resolved =
        ConnectionResolver.resolveDefault(null, ServerDirectory.of(List.of()), choice);

    assertTrue(resolved.usesMssIdentity());
    assertEquals(List.of(resolved), choice.writes);
  }

  @Test
  void escolhaSalvaComumNaoERegravada() {
    MemoryChoice choice = new MemoryChoice();
    choice.saved = new ServerPreset("Casa", "192.168.0.5", 1099, true);

    ServerPreset resolved =
        ConnectionResolver.resolveDefault(null, ServerDirectory.of(List.of()), choice);

    assertEquals(choice.saved, resolved);
    assertTrue(choice.writes.isEmpty());
  }

  @Test
  void linhaDeComandoComIdentidadeVenceAEscolhaSalva() {
    MemoryChoice choice = new MemoryChoice();
    choice.saved = new ServerPreset("Casa", "192.168.0.5", 1099, true);
    ServerPreset cli = Main.withIdentityOption(CLI, "localhost:9100", true);

    assertEquals(
        cli, ConnectionResolver.resolveDefault(cli, ServerDirectory.of(List.of()), choice));
  }
}
