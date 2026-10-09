package br.com.mss.domino.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.mss.domino.net.GameEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ClientEventBus} — idempotência de {@link ClientEventBus#subscribe} (Fase 4.5-2, ADR-0024):
 * {@code MainWindow} chamava {@code subscribe} a cada (re)conexão, e sem essa defesa cada evento
 * acabava entregue em dobro (triplo, ...) a cada reconexão adicional.
 */
class ClientEventBusTest {

  @Test
  void subscribe_omesmoObserverDuasVezes_recebeCadaEventoUmaVezSo() {
    ClientEventBus bus = new ClientEventBus();
    List<GameEvent> received = new ArrayList<>();
    MatchObserver observer = (event, view) -> received.add(event);

    bus.subscribe(observer);
    bus.subscribe(observer); // "reconectou de novo" — não deveria duplicar a inscrição

    bus.emit(new GameEvent.MatchEnded(null), null);

    assertEquals(1, received.size());
  }

  @Test
  void subscribe_observersDiferentes_recebemNormalmente() {
    ClientEventBus bus = new ClientEventBus();
    List<GameEvent> receivedA = new ArrayList<>();
    List<GameEvent> receivedB = new ArrayList<>();

    bus.subscribe((event, view) -> receivedA.add(event));
    bus.subscribe((event, view) -> receivedB.add(event));

    bus.emit(new GameEvent.MatchEnded(null), null);

    assertEquals(1, receivedA.size());
    assertEquals(1, receivedB.size());
  }

  @Test
  void unsubscribe_paraDeReceber() {
    ClientEventBus bus = new ClientEventBus();
    List<GameEvent> received = new ArrayList<>();
    MatchObserver observer = (event, view) -> received.add(event);

    bus.subscribe(observer);
    bus.unsubscribe(observer);
    bus.emit(new GameEvent.MatchEnded(null), null);

    assertEquals(0, received.size());
  }
}
