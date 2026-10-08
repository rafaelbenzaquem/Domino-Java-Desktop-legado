package br.com.mss.domino.app;

import br.com.mss.domino.net.GameEvent;

/**
 * Observador de UI (ADR-0016). Recebe cada mudança já aplicada à projeção; só reage, nunca chama a
 * rede nem roda regra (ADR-0002).
 *
 * @see ClientEventBus
 */
@FunctionalInterface
public interface MatchObserver {

  /**
   * @param event o evento aplicado, ou {@code null} na primeira notificação (pintura inicial a
   *     partir do snapshot)
   * @param view a projeção resultante
   */
  void onMatchChanged(GameEvent event, ClientGameView view);
}
