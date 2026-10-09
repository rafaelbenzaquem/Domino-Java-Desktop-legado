package br.com.mss.domino.ui.sandbox;

import br.com.mss.domino.domain.End;
import br.com.mss.domino.domain.GameEngine;
import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.domain.GameState;
import br.com.mss.domino.domain.Hand;
import br.com.mss.domino.domain.Move;
import br.com.mss.domino.domain.Seat;
import br.com.mss.domino.domain.Tile;
import br.com.mss.domino.domain.setup.Dealer;
import br.com.mss.domino.ui.BoardView;
import br.com.mss.domino.ui.HandPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Optional;
import java.util.Random;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

/**
 * Bancada de dev (fora do jar): abre uma janela para conferir visualmente a renderização do
 * tabuleiro sobre o modelo do domínio.
 *
 * <pre>./mvnw -q test-compile exec:java@sandbox</pre>
 */
public final class BoardSandbox {

  private static final Color FELT = new Color(0x2E, 0x6B, 0x4F);

  private final BoardView board = new BoardView();
  private final HandPanel hand = new HandPanel();
  private final JLabel status = new JLabel(" ");
  private final JTextArea chat = new JTextArea();

  private BoardSandbox() {}

  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> new BoardSandbox().show());
  }

  private void show() {
    JFrame frame = new JFrame("Domino — bancada do tabuleiro (Fase 2)");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

    JComboBox<Scenario> picker = new JComboBox<>(Scenario.values());
    picker.addActionListener(e -> load((Scenario) picker.getSelectedItem()));

    JPanel top = new JPanel(new BorderLayout());
    top.add(new JLabel("  Cenário: "), BorderLayout.WEST);
    top.add(picker, BorderLayout.CENTER);

    // mesa (verde) = tabuleiro com a mão do jogador dentro, embaixo. O tabuleiro
    // reserva a altura da mão para centrar a cadeia acima dela.
    int handHeight = hand.getPreferredSize().height + 8;
    board.setLayout(new BorderLayout());
    board.add(hand, BorderLayout.SOUTH);
    board.setBottomReserve(handHeight);
    JPanel table = new JPanel(new BorderLayout());
    table.setBackground(FELT);
    table.add(board, BorderLayout.CENTER);

    // chat à direita da mesa, na vertical (placeholder — vem de verdade na Fase 3, ADR-0009)
    chat.setEditable(false);
    chat.setLineWrap(true);
    chat.setWrapStyleWord(true);
    chat.setText(
        "Sistema: partida iniciada\n"
            + "Sistema: Ana abriu com 6-6\n"
            + "Sistema: Bruno comprou 1 peça\n"
            + "Sistema: Bruno passou a vez\n"
            + "Ana: 🁫 quase lá\n"
            + "Carla: 😅\n\n"
            + "(placeholder — chat real na Fase 3)");
    JScrollPane chatScroll =
        new JScrollPane(
            chat,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    chatScroll.setPreferredSize(new Dimension(230, 0));
    chatScroll.setBorder(BorderFactory.createTitledBorder("Chat"));

    frame.setLayout(new BorderLayout());
    frame.add(top, BorderLayout.NORTH);
    frame.add(table, BorderLayout.CENTER);
    frame.add(chatScroll, BorderLayout.EAST);
    frame.add(status, BorderLayout.SOUTH);
    frame.setMinimumSize(new Dimension(1040, 680));
    frame.pack();
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);

    load(Scenario.MESA_VAZIA);
  }

  private void load(Scenario scenario) {
    GameState state = scenario.build();
    board.setState(state);
    board.setHoverCandidate(null);

    Seat viewer = state.currentSeat();
    Hand viewerHand = state.hand(viewer);
    hand.setHand(viewerHand == null ? Hand.empty() : viewerHand);
    hand.setPlayable(
        tile ->
            state.line().isEmpty()
                ? tile.equals(state.openingTile())
                : state.line().fitsEither(tile));
    hand.setOnSelect(
        tile -> {
          board.setHoverCandidate(tile);
          if (tile == null) {
            status.setText("  clique numa peça da mão para ver onde encaixa");
          } else {
            boolean l = state.line().fits(tile, End.LEFT);
            boolean r = state.line().fits(tile, End.RIGHT);
            status.setText(
                "  "
                    + tile
                    + " encaixa: "
                    + (l ? "esquerda " : "")
                    + (r ? "direita" : "")
                    + (!l && !r ? "em nenhuma ponta" : ""));
          }
        });

    if (state.isFinished()) {
      status.setText(
          "  Fim: "
              + state.outcome().reason()
              + " — vencedor(es) "
              + state.outcome().winningSeats());
    } else {
      status.setText("  Vez de " + viewer + " (cadeira " + viewer.chair() + ")");
    }
  }

  private enum Scenario {
    MESA_VAZIA,
    CADEIA_CURTA,
    CADEIA_LONGA_COM_DOBRAS,
    PARTIDA_TERMINADA;

    GameState build() {
      GameEngine engine = new GameEngine();
      Dealer dealer = new Dealer(new Random(2024));
      GameState state = GameState.start(dealer.deal(GameMode.FREE_FOR_ALL, 3));
      int limit =
          switch (this) {
            case MESA_VAZIA -> 0;
            case CADEIA_CURTA -> 4;
            case CADEIA_LONGA_COM_DOBRAS -> 40;
            case PARTIDA_TERMINADA -> 999;
          };
      int guard = 0;
      while (!state.isFinished() && guard < limit && guard < 999) {
        guard++;
        Seat seat = state.currentSeat();
        Optional<Move> play = firstPlayable(state, seat);
        if (play.isPresent()) {
          state = engine.applyMove(state, play.get()).state();
        } else if (!state.boneyard().isEmpty()) {
          Tile drawn = dealer.drawFrom(state.boneyard());
          state = engine.autoBuy(state, seat, drawn).state();
        } else {
          state = engine.applyMove(state, Move.pass(seat)).state();
        }
      }
      return state;
    }

    private static Optional<Move> firstPlayable(GameState state, Seat seat) {
      Hand h = state.hand(seat);
      if (state.line().isEmpty()) {
        return h.contains(state.openingTile())
            ? Optional.of(Move.play(seat, state.openingTile(), End.LEFT))
            : Optional.empty();
      }
      for (Tile tile : h.tiles()) {
        if (state.line().fits(tile, End.LEFT)) {
          return Optional.of(Move.play(seat, tile, End.LEFT));
        }
        if (state.line().fits(tile, End.RIGHT)) {
          return Optional.of(Move.play(seat, tile, End.RIGHT));
        }
      }
      return Optional.empty();
    }
  }
}
