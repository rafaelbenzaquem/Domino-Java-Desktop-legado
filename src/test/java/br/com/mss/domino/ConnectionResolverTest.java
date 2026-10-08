package br.com.mss.domino;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.app.ServerChoiceStore;
import br.com.mss.domino.net.NetworkConfig;
import br.com.mss.domino.net.config.ServerDirectory;
import br.com.mss.domino.net.config.ServerPreset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link ConnectionResolver} — ordem de prioridade da resolução do servidor (ADR-0022). */
class ConnectionResolverTest {

  /** Fake em memória — sem tocar {@code Preferences} de verdade. */
  private static final class FakeServerChoiceStore implements ServerChoiceStore {
    private Optional<ServerPreset> saved = Optional.empty();

    @Override
    public Optional<ServerPreset> lastChoice() {
      return saved;
    }

    @Override
    public void remember(ServerPreset preset) {
      saved = Optional.of(preset);
    }
  }

  private static final ServerChoiceStore SEM_ESCOLHA_SALVA = new FakeServerChoiceStore();

  @Test
  void cliExplicitaTemPrioridadeSobreEscolhaSalvaEPresetPadrao() {
    ServerPreset cliServer = new ServerPreset("linha de comando", "1.2.3.4", 6000, true);
    ServerDirectory directory =
        ServerDirectory.of(List.of(new ServerPreset("Local", "9.9.9.9", 5050, true)));
    FakeServerChoiceStore saved = new FakeServerChoiceStore();
    saved.remember(new ServerPreset("Casa", "192.168.0.5", 7000, false));

    ServerPreset resolved = ConnectionResolver.resolveDefault(cliServer, directory, saved);

    assertEquals("1.2.3.4", resolved.host());
    assertEquals(6000, resolved.port());
  }

  @Test
  void escolhaSalvaTemPrioridadeSobreOPresetPadrao() {
    ServerDirectory directory =
        ServerDirectory.of(List.of(new ServerPreset("Local", "9.9.9.9", 5050, true)));
    FakeServerChoiceStore saved = new FakeServerChoiceStore();
    saved.remember(new ServerPreset("Casa", "192.168.0.5", 7000, false));

    ServerPreset resolved = ConnectionResolver.resolveDefault(null, directory, saved);

    assertEquals(new ServerPreset("Casa", "192.168.0.5", 7000, false), resolved);
  }

  @Test
  void semEscolhaSalvaUsaOPresetPadraoDoDiretorio() {
    ServerDirectory directory =
        ServerDirectory.of(List.of(new ServerPreset("Casa", "192.168.0.5", 6000, true)));

    ServerPreset resolved = ConnectionResolver.resolveDefault(null, directory, SEM_ESCOLHA_SALVA);

    assertEquals(new ServerPreset("Casa", "192.168.0.5", 6000, true), resolved);
  }

  @Test
  void semNadaCaiParaLocalhost() {
    ServerPreset resolved =
        ConnectionResolver.resolveDefault(null, ServerDirectory.of(List.of()), SEM_ESCOLHA_SALVA);

    assertEquals("localhost", resolved.host());
    assertEquals(NetworkConfig.DEFAULT_PORT, resolved.port());
  }

  @Test
  void storeNuloEEquivalenteASemEscolhaSalva() {
    ServerDirectory directory =
        ServerDirectory.of(List.of(new ServerPreset("Casa", "192.168.0.5", 6000, true)));

    ServerPreset resolved = ConnectionResolver.resolveDefault(null, directory, null);

    assertTrue(resolved.name().equals("Casa"));
  }
}
