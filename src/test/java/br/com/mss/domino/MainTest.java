package br.com.mss.domino;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.net.config.ServerPreset;
import org.junit.jupiter.api.Test;

/**
 * Parsing de CLI do {@link Main} — {@code --embedded-server}/{@code --port=} (ADR-0020), {@code
 * --server=} (ADR-0022).
 */
class MainTest {

  @Test
  void hasFlag_semArgumentos_ehFalso() {
    assertFalse(Main.hasFlag(new String[0], "--embedded-server"));
  }

  @Test
  void hasFlag_presente_ehVerdadeiro() {
    assertTrue(Main.hasFlag(new String[] {"--embedded-server"}, "--embedded-server"));
  }

  @Test
  void hasFlag_entreOutrosArgumentos_ehVerdadeiro() {
    assertTrue(
        Main.hasFlag(new String[] {"--port=1100", "--embedded-server"}, "--embedded-server"));
  }

  @Test
  void hasFlag_ausente_ehFalso() {
    assertFalse(Main.hasFlag(new String[] {"--port=1100"}, "--embedded-server"));
  }

  @Test
  void intOption_ausente_devolveDefault() {
    assertEquals(1099, Main.intOption(new String[0], "--port", 1099));
  }

  @Test
  void intOption_presente_devolveOValor() {
    assertEquals(1100, Main.intOption(new String[] {"--port=1100"}, "--port", 1099));
  }

  @Test
  void intOption_invalido_devolveDefault() {
    assertEquals(1099, Main.intOption(new String[] {"--port=abc"}, "--port", 1099));
  }

  @Test
  void intOption_entreOutrosArgumentos_devolveOValor() {
    assertEquals(
        1100, Main.intOption(new String[] {"--embedded-server", "--port=1100"}, "--port", 1099));
  }

  @Test
  void warnUnknownArgs_naoLancaComArgumentosConhecidos() {
    assertDoesNotThrow(
        () -> Main.warnUnknownArgs(new String[] {"--embedded-server", "--port=1100"}));
  }

  @Test
  void warnUnknownArgs_naoLancaComArgumentoDesconhecido() {
    // fase-3c-2: "--embedded-serve" (typo) e "--port 1010" (sem "=") eram ignorados em silêncio,
    // abrindo como cliente puro sem nenhum aviso — este teste só garante que o caminho de aviso
    // não quebra; a mensagem em si é conferida no roteiro manual (não há appender de teste aqui).
    assertDoesNotThrow(() -> Main.warnUnknownArgs(new String[] {"--embedded-serve", "1010"}));
  }

  @Test
  void hasFlag_noLanDiscovery_reconhece() {
    assertTrue(Main.hasFlag(new String[] {"--no-lan-discovery"}, Main.FLAG_NO_LAN_DISCOVERY));
  }

  @Test
  void warnUnknownArgs_naoAvisaSobreNoLanDiscovery() {
    // --no-lan-discovery (ADR-0021) é flag conhecida — não deve cair no aviso de desconhecido.
    assertDoesNotThrow(
        () -> Main.warnUnknownArgs(new String[] {"--embedded-server", Main.FLAG_NO_LAN_DISCOVERY}));
  }

  @Test
  void warnUnknownArgs_naoAvisaSobreServer() {
    // --server=host:porta (ADR-0022) é opção conhecida — não deve cair no aviso de desconhecido.
    assertDoesNotThrow(() -> Main.warnUnknownArgs(new String[] {"--server=192.168.0.5:1100"}));
  }

  @Test
  void stringOption_ausente_devolveNull() {
    assertNull(Main.stringOption(new String[0], "--server"));
  }

  @Test
  void stringOption_presente_devolveOValor() {
    assertEquals(
        "192.168.0.5:1100",
        Main.stringOption(new String[] {"--server=192.168.0.5:1100"}, "--server"));
  }

  @Test
  void parseServerOption_nulo_devolveNull() {
    assertNull(Main.parseServerOption(null));
  }

  @Test
  void parseServerOption_semDoisPontos_usaAPortaPadrao() {
    ServerPreset preset = Main.parseServerOption("192.168.0.5");

    assertEquals("192.168.0.5", preset.host());
    assertEquals(1099, preset.port());
  }

  @Test
  void parseServerOption_comPorta_usaAPortaInformada() {
    ServerPreset preset = Main.parseServerOption("192.168.0.5:1100");

    assertEquals("192.168.0.5", preset.host());
    assertEquals(1100, preset.port());
  }

  @Test
  void parseServerOption_portaInvalida_caiParaAPadrao() {
    ServerPreset preset = Main.parseServerOption("192.168.0.5:abc");

    assertEquals("192.168.0.5", preset.host());
    assertEquals(1099, preset.port());
  }
}
