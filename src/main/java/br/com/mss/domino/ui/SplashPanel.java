package br.com.mss.domino.ui;

import br.com.mss.domino.domain.End;
import br.com.mss.domino.domain.Line;
import br.com.mss.domino.domain.Pip;
import br.com.mss.domino.domain.Tile;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

/**
 * Tela inicial do {@code Main} (Fase 4-4/4.5-1): uma mini cadeia de dominó que se monta sozinha em
 * loop, com o título por cima. Abre com um <b>carrão</b> (peça dupla, sempre desenhada
 * perpendicular à cadeia — mesma regra do jogo de verdade) e cresce **uma peça de cada vez**, ora
 * pra esquerda ora pra direita, até ter que <b>dobrar pra cima ou pra baixo</b> por falta de espaço
 * — reaproveita o mesmo {@link BoardGeometry}/{@link BoardView} do tabuleiro de verdade (nunca
 * reimplementa layout/dobra por conta própria), então o comportamento é sempre consistente com o
 * board real. Cada rodada sorteia as peças de um <b>monte de 28 únicas</b> ({@link Tile#fullSet()})
 * sem repetição — quando nenhuma peça restante encaixa em nenhuma ponta (ou o monte acaba), a
 * rodada trava, segura um instante e reinicia com um monte novo. Um clique congela a cadeia onde
 * estiver.
 */
public final class SplashPanel extends JPanel {

  private static final Color FELT = new Color(0x2E, 0x6B, 0x4F);
  private static final Color TITLE_COLOR = Color.WHITE;
  private static final Color SUBTITLE_COLOR = new Color(0xDD, 0xE8, 0xE1);

  private static final int HOLD_TICKS = 8; // cadeia travada/completa, parada por ~8 tiques

  private final Random random = new Random();
  private final BoardView board = new BoardView();
  private final Timer timer = new Timer(320, e -> tick());
  private final List<Tile> boneyard = new ArrayList<>(); // as peças do monte ainda não usadas

  private Line line = Line.EMPTY;
  private Tile opening;
  private int holdTicks;
  private boolean frozen;

  public SplashPanel() {
    super(new BorderLayout());
    setOpaque(true);
    setBackground(FELT);
    setPreferredSize(new Dimension(460, 240));

    JLabel title = new JLabel("Dominó", SwingConstants.CENTER);
    title.setForeground(TITLE_COLOR);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 30f));

    JLabel subtitle = new JLabel("escolha um servidor e conecte para jogar", SwingConstants.CENTER);
    subtitle.setForeground(SUBTITLE_COLOR);
    subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));

    add(title, BorderLayout.NORTH);
    add(board, BorderLayout.CENTER);
    add(subtitle, BorderLayout.SOUTH);

    reshuffle();

    MouseAdapter freezeOnClick =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            freeze();
          }
        };
    addMouseListener(freezeOnClick);
    board.addMouseListener(freezeOnClick); // clique sobre a mesa também congela

    timer.setInitialDelay(400);
    timer.start();
  }

  /** Para a animação onde a cadeia estiver. Chamado ao clicar; idempotente. */
  public void freeze() {
    if (frozen) {
      return;
    }
    frozen = true;
    timer.stop();
  }

  /** Encerra o timer — chamado se a tela some de vez (não há caso assim hoje, mas por hygiene). */
  public void stop() {
    timer.stop();
  }

  private void tick() {
    if (holdTicks > 0) {
      holdTicks--;
      if (holdTicks == 0) {
        reshuffle();
      }
      return;
    }
    if (boneyard.isEmpty()) {
      holdTicks = HOLD_TICKS; // monte acabou: 28 peças na mesa, cadeia completa
      return;
    }
    growOnce();
  }

  /** Reabre a mesa com um monte de 28 peças novo, embaralhado, e um carrão sorteado dele. */
  private void reshuffle() {
    boneyard.clear();
    boneyard.addAll(Tile.fullSet());
    Collections.shuffle(boneyard, random);

    opening = boneyard.stream().filter(Tile::isDouble).findFirst().orElseThrow();
    boneyard.remove(opening);

    line = Line.opening(opening);
    board.setBoard(line, opening);
    board.setLastPlaced(null);
  }

  /**
   * Tenta encaixar uma peça do monte numa ponta sorteada (esquerda ou direita primeiro, na sorte);
   * se essa ponta não tiver peça restante que encaixe, tenta a outra. Sem nenhuma peça encaixando
   * em ponta nenhuma, a rodada travou (igual um jogo de verdade travando) — segura e reinicia.
   */
  private void growOnce() {
    End first = random.nextBoolean() ? End.LEFT : End.RIGHT;
    End second = first == End.LEFT ? End.RIGHT : End.LEFT;
    if (tryGrow(first) || tryGrow(second)) {
      board.setBoard(line, opening);
    } else {
      holdTicks = HOLD_TICKS;
    }
  }

  private boolean tryGrow(End end) {
    Pip needed = end == End.LEFT ? line.leftEnd() : line.rightEnd();
    List<Tile> candidates = boneyard.stream().filter(t -> t.has(needed)).toList();
    if (candidates.isEmpty()) {
      return false;
    }
    Tile tile = candidates.get(random.nextInt(candidates.size()));
    line = line.place(tile, end);
    boneyard.remove(tile);
    board.setLastPlaced(tile);
    return true;
  }
}
