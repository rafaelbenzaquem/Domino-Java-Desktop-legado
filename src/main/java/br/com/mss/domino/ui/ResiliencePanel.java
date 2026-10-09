package br.com.mss.domino.ui;

import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.GameEvent;
import br.com.mss.domino.net.TransportException;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Banner de queda/reconexão/eliminação/aborto (ADR-0013). Some quando ninguém está caído; enquanto
 * há alguém, mostra a contagem até a janela de 5 min esgotar e os botões cabíveis: "Pedir
 * eliminação" (modo "um contra todos", a qualquer momento) ou "Pedir aborto" (modo duplas, só
 * depois que a janela esgota); durante uma votação, "Confirmar"/"Recusar" no lugar do pedido. Cru —
 * polimento é da Fase 4.
 */
public final class ResiliencePanel extends JPanel {

  /** Pedidos que o painel dispara — a UI nunca fala com o transporte direto. */
  public interface Actions {
    void requestElimination(int seat) throws TransportException;

    void confirmElimination() throws TransportException;

    void declineElimination() throws TransportException;

    void requestAbort() throws TransportException;

    void confirmAbort() throws TransportException;

    void declineAbort() throws TransportException;
  }

  private final JLabel info = new JLabel(" ");
  private final JButton primary = new JButton();
  private final JButton confirm = new JButton();
  private final JButton decline = new JButton("Recusar");

  private Actions actions;
  private Consumer<String> onError = m -> {};
  private GameMode mode = GameMode.FREE_FOR_ALL;
  private int disconnectedSeat = -1;
  private long reconnectDeadline;
  private boolean voteActive;

  public ResiliencePanel() {
    super(new FlowLayout(FlowLayout.LEFT, 8, 2));
    info.setFont(info.getFont().deriveFont(Font.ITALIC));
    add(info);
    add(primary);
    add(confirm);
    add(decline);
    primary.addActionListener(e -> fire(this::firePrimary));
    confirm.addActionListener(e -> fire(this::fireConfirm));
    decline.addActionListener(e -> fire(this::fireDecline));
    setVisible(false);
    new Timer(500, e -> refresh()).start();
  }

  public void setActions(Actions actions) {
    this.actions = actions;
  }

  public void setOnError(Consumer<String> onError) {
    this.onError = onError;
  }

  /** Reinicia o painel para uma partida nova (chamado ao começar/entrar). */
  public void reset(GameMode mode) {
    this.mode = mode;
    disconnectedSeat = -1;
    voteActive = false;
    refresh();
  }

  /** Reage aos eventos de resiliência; os demais são ignorados (a UI geral já reage via a view). */
  public void onEvent(GameEvent event) {
    switch (event) {
      case GameEvent.PlayerDisconnected e -> {
        disconnectedSeat = e.seat();
        reconnectDeadline = e.reconnectDeadline();
      }
      case GameEvent.PlayerReconnected e -> clearIfSameSeat(e.seat());
      case GameEvent.PlayerEliminated e -> {
        clearIfSameSeat(e.seat());
        voteActive = false;
      }
      case GameEvent.EliminationRequested ignored -> voteActive = true;
      case GameEvent.EliminationCancelled ignored -> voteActive = false;
      case GameEvent.AbortRequested ignored -> voteActive = true;
      case GameEvent.AbortCancelled ignored -> voteActive = false;
      case GameEvent.MatchEnded ignored -> {
        disconnectedSeat = -1;
        voteActive = false;
      }
      default -> {
        // não é da resiliência — nada a fazer aqui.
      }
    }
    refresh();
  }

  private void clearIfSameSeat(int seat) {
    if (seat == disconnectedSeat) {
      disconnectedSeat = -1;
    }
  }

  private void refresh() {
    if (disconnectedSeat < 0) {
      setVisible(false);
      return;
    }
    setVisible(true);
    long remaining = Math.max(0, reconnectDeadline - System.currentTimeMillis() + 999) / 1000;
    boolean expired = remaining <= 0;
    info.setText(
        "Cadeira "
            + (disconnectedSeat + 1)
            + " caiu"
            + (expired
                ? " — janela de reconexão esgotada."
                : " — reconecta em " + remaining + "s"));

    boolean freeForAll = mode == GameMode.FREE_FOR_ALL;
    primary.setText(freeForAll ? "Pedir eliminação" : "Pedir aborto");
    primary.setEnabled(freeForAll || expired);
    primary.setVisible(!voteActive);
    confirm.setText(freeForAll ? "Confirmar eliminação" : "Confirmar aborto");
    confirm.setVisible(voteActive);
    decline.setVisible(voteActive);
  }

  private void firePrimary() throws TransportException {
    if (mode == GameMode.FREE_FOR_ALL) {
      actions.requestElimination(disconnectedSeat);
    } else {
      actions.requestAbort();
    }
  }

  private void fireConfirm() throws TransportException {
    if (mode == GameMode.FREE_FOR_ALL) {
      actions.confirmElimination();
    } else {
      actions.confirmAbort();
    }
  }

  private void fireDecline() throws TransportException {
    if (mode == GameMode.FREE_FOR_ALL) {
      actions.declineElimination();
    } else {
      actions.declineAbort();
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws TransportException;
  }

  private void fire(ThrowingAction action) {
    try {
      action.run();
    } catch (TransportException ex) {
      onError.accept(ex.getMessage());
    }
  }
}
