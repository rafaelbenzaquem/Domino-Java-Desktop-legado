package br.com.mss.domino.app;

import br.com.mss.domino.net.GameEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * O <i>Subject</i> do padrão Observer no cliente (ADR-0016): o {@link MatchController} publica aqui
 * (já na EDT), e cada componente de UI registrado é notificado. A UI nunca fala com a rede
 * diretamente.
 */
public final class ClientEventBus {

  private final List<MatchObserver> observers = new CopyOnWriteArrayList<>();

  /**
   * Idempotente (Fase 4.5-2, ADR-0024): não adiciona de novo se {@code observer} já está na lista —
   * cinto-e-suspensório contra a causa raiz de verdade da notificação de vitória em dobro (era
   * {@code MainWindow} chamando {@code subscribe} a cada conexão/reconexão, em vez de uma vez só no
   * construtor).
   */
  public void subscribe(MatchObserver observer) {
    if (!observers.contains(observer)) {
      observers.add(observer);
    }
  }

  public void unsubscribe(MatchObserver observer) {
    observers.remove(observer);
  }

  void emit(GameEvent event, ClientGameView view) {
    for (MatchObserver observer : observers) {
      observer.onMatchChanged(event, view);
    }
  }
}
