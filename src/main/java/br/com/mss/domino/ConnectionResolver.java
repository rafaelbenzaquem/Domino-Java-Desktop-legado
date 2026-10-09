package br.com.mss.domino;

import br.com.mss.domino.app.ServerChoiceStore;
import br.com.mss.domino.net.NetworkConfig;
import br.com.mss.domino.net.config.ServerDirectory;
import br.com.mss.domino.net.config.ServerPreset;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Lógica pura de resolução do servidor (ADR-0022) — sem Swing, sem rede, só decide a partir do que
 * já foi lido em outro lugar. Público (diferente do equivalente no TchowStrick) porque quem chama é
 * a {@code ui.MainWindow}, em outro pacote.
 */
public final class ConnectionResolver {

  private ConnectionResolver() {}

  /**
   * Servidor sugerido ao abrir o app: {@code --server=} explícito (linha de comando) tem prioridade
   * máxima; senão a última escolha manual guardada em {@code savedChoice}; senão o preset {@code
   * default} do {@code directory} (externo ou embutido); se nem isso existir, cai num {@code
   * localhost} de última instância — nunca lança. {@code --embedded-server} não entra aqui: quando
   * ativo, o {@link Main} já decide o destino da conexão direto, sem passar por este resolvedor.
   */
  public static ServerPreset resolveDefault(
      ServerPreset cliServer, ServerDirectory directory, ServerChoiceStore savedChoice) {
    return resolveDefault(cliServer, directory, savedChoice, ServerDirectory::withOfficialIdentity);
  }

  /**
   * Como {@link #resolveDefault(ServerPreset, ServerDirectory, ServerChoiceStore)}; {@code upgrade}
   * atualiza uma escolha salva antiga (oficial sem identidade MSS → preset oficial com identidade,
   * M1), e a escolha atualizada é regravada.
   */
  static ServerPreset resolveDefault(
      ServerPreset cliServer,
      ServerDirectory directory,
      ServerChoiceStore savedChoice,
      UnaryOperator<ServerPreset> upgrade) {
    if (cliServer != null) {
      return cliServer;
    }
    if (savedChoice != null) {
      Optional<ServerPreset> remembered = savedChoice.lastChoice();
      if (remembered.isPresent()) {
        ServerPreset current = upgrade.apply(remembered.get());
        if (!current.equals(remembered.get())) {
          savedChoice.remember(current);
        }
        return current;
      }
    }
    return directory
        .defaultPreset()
        .orElse(new ServerPreset("localhost", "localhost", NetworkConfig.DEFAULT_PORT, true));
  }
}
