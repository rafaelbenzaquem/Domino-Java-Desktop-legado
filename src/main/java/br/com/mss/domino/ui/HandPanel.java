package br.com.mss.domino.ui;

import br.com.mss.domino.domain.Hand;
import br.com.mss.domino.domain.Tile;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.JComponent;

/**
 * A mão do jogador: peças <b>em pé</b>, sobre a mesa verde, numa linha. Cada peça é clicável; as
 * jogáveis (segundo o {@code playable} recebido — tipicamente {@code line::fitsEither}, do domínio)
 * ficam nítidas e as demais esmaecidas. A UI não recalcula regra.
 */
public final class HandPanel extends JComponent {

  static final Color FELT = new Color(0x2E, 0x6B, 0x4F);
  private static final Color DIM = new Color(0x2E, 0x6B, 0x4F, 150);
  private static final Color PLAYABLE_RING = new Color(0xFF, 0xF6, 0xD8);
  private static final Color SELECTED = new Color(0xF4, 0xD0, 0x3F);

  private final TileMetrics metrics = TileMetrics.standard();
  private final int spacing = 10;
  private final int padding = 12;

  private transient Hand hand = Hand.empty();
  private transient Predicate<Tile> playable = t -> false;
  private transient Tile selected;
  private transient Consumer<Tile> onSelect = t -> {};

  public HandPanel() {
    setBackground(FELT);
    setOpaque(true);
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            tileAt(e.getPoint().x, e.getPoint().y)
                .ifPresent(
                    tile -> {
                      selected = tile.equals(selected) ? null : tile;
                      onSelect.accept(selected);
                      repaint();
                    });
          }
        });
  }

  public void setHand(Hand hand) {
    this.hand = hand;
    this.selected = null;
    revalidate();
    repaint();
  }

  public void setPlayable(Predicate<Tile> playable) {
    this.playable = playable;
    repaint();
  }

  public void setOnSelect(Consumer<Tile> onSelect) {
    this.onSelect = onSelect;
  }

  public Tile selected() {
    return selected;
  }

  @Override
  public Dimension getPreferredSize() {
    int n = Math.max(hand.size(), 1);
    int w = 2 * padding + n * metrics.shortSide() + (n - 1) * spacing;
    int h = 2 * padding + metrics.longSide();
    return new Dimension(w, h);
  }

  private List<Rectangle> tileRects() {
    List<Rectangle> rects = new ArrayList<>();
    int step = metrics.shortSide() + spacing;
    int y = (getHeight() - metrics.longSide()) / 2;
    int x = padding;
    for (int i = 0; i < hand.size(); i++) {
      rects.add(new Rectangle(x, y, metrics.shortSide(), metrics.longSide()));
      x += step;
    }
    return rects;
  }

  private Optional<Tile> tileAt(int px, int py) {
    List<Rectangle> rects = tileRects();
    for (int i = 0; i < rects.size(); i++) {
      if (rects.get(i).contains(px, py)) {
        return Optional.of(hand.tiles().get(i));
      }
    }
    return Optional.empty();
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(FELT);
      g.fillRect(0, 0, getWidth(), getHeight());

      List<Rectangle> rects = tileRects();
      for (int i = 0; i < hand.size(); i++) {
        Tile tile = hand.tiles().get(i);
        Rectangle r = rects.get(i);
        boolean canPlay = playable.test(tile);
        boolean isSelected = tile.equals(selected);

        TilePainter.paint(g, r, Orientation.VERTICAL, tile.low(), tile.high(), false);

        if (!canPlay) {
          int arc = Math.max(6, r.width / 5);
          g.setColor(DIM);
          g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc);
        } else {
          g.setColor(PLAYABLE_RING);
          g.setStroke(new BasicStroke(2f));
          g.drawRoundRect(r.x - 2, r.y - 2, r.width + 4, r.height + 4, 12, 12);
        }

        if (isSelected) {
          g.setColor(SELECTED);
          g.setStroke(new BasicStroke(3f));
          g.drawRoundRect(r.x - 4, r.y - 4, r.width + 8, r.height + 8, 14, 14);
        }
      }
    } finally {
      g.dispose();
    }
  }
}
