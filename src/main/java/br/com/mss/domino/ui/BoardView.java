package br.com.mss.domino.ui;

import br.com.mss.domino.domain.End;
import br.com.mss.domino.domain.GameState;
import br.com.mss.domino.domain.Line;
import br.com.mss.domino.domain.Tile;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.Optional;
import javax.swing.JComponent;

/**
 * Renderiza a cadeia de peças a partir de {@link GameState}/{@link Line}, com pintura vetorial
 * (Graphics2D), sem imagens e sem mutar o modelo. Realça a última peça jogada e a ponta onde a peça
 * "candidata" (selecionada na mão) encaixaria.
 */
public final class BoardView extends JComponent {

  private static final Color FELT = new Color(0x2E, 0x6B, 0x4F);
  private static final Color HINT = new Color(0xFF, 0xFF, 0xFF, 140);
  private static final Color LAST_MOVE = new Color(0xF4, 0xD0, 0x3F);
  private static final Color FIT_END = new Color(0xF4, 0xD0, 0x3F);

  private static final int INSET = 14;

  private final transient BoardGeometry geometry = BoardGeometry.standard();

  private transient Line line = Line.EMPTY;
  private transient Tile openingTile;
  private transient Tile lastPlaced;
  private transient Tile hoverCandidate;
  private int bottomReserve;
  private boolean interactive = true;

  // guardados no último paint para hit-testing de cliques (Fase 3)
  private transient BoardGeometry.BoardLayout lastLayout;
  private transient AffineTransform lastTransform = new AffineTransform();

  public BoardView() {
    setPreferredSize(new Dimension(720, 460));
    setBackground(FELT);
    setOpaque(true);
  }

  /** Estado completo do domínio (bancada de dev). */
  public void setState(GameState state) {
    this.line = state == null ? Line.EMPTY : state.line();
    this.openingTile = state == null ? null : state.openingTile();
    repaint();
  }

  /** Só o que o tabuleiro precisa: a cadeia e a peça de abertura (cliente em rede). */
  public void setBoard(Line line, Tile openingTile) {
    this.line = line == null ? Line.EMPTY : line;
    this.openingTile = openingTile;
    repaint();
  }

  public void setLastPlaced(Tile lastPlaced) {
    this.lastPlaced = lastPlaced;
    repaint();
  }

  public void setHoverCandidate(Tile candidate) {
    this.hoverCandidate = candidate;
    repaint();
  }

  /** Altura (px) reservada embaixo para a mão do jogador — a cadeia é centrada acima dela. */
  public void setBottomReserve(int px) {
    this.bottomReserve = Math.max(0, px);
    repaint();
  }

  /**
   * Fase 4-3: {@code false} suspende o hit-testing de {@link #endAt} e a dica tracejada da ponta
   * onde a peça candidata encaixaria — fora de partida em andamento (terminada, ou pausada
   * esperando reconexão de quem está na vez), clique/hover no tabuleiro não fazem mais sentido
   * visual, mesmo que {@code hoverCandidate} ainda esteja com um valor de uma vez anterior.
   */
  public void setInteractive(boolean interactive) {
    this.interactive = interactive;
    repaint();
  }

  /** Qual ponta está sob o ponto (coordenadas do componente), se alguma. */
  public Optional<End> endAt(Point p) {
    if (!interactive || lastLayout == null || lastLayout.isEmpty()) {
      return Optional.empty();
    }
    Point2D model;
    try {
      model = lastTransform.inverseTransform(p, null);
    } catch (NoninvertibleTransformException e) {
      return Optional.empty();
    }
    if (lastLayout.left().hitbox().contains(model)) {
      return Optional.of(End.LEFT);
    }
    if (lastLayout.right().hitbox().contains(model)) {
      return Optional.of(End.RIGHT);
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

      if (line.isEmpty()) {
        lastLayout = null;
        paintOpeningHint(g);
        return;
      }

      // Layout em coordenadas de mundo (centrado em 0,0); aqui só encaixamos a
      // bounding box na área livre da mesa, com UMA transformação global (Regra 5).
      int areaW = Math.max(240, getWidth() - 2 * INSET);
      int areaH = Math.max(160, getHeight() - 2 * INSET - bottomReserve);
      BoardGeometry.BoardLayout layout = geometry.layout(line, openingTile);
      lastLayout = layout;
      lastTransform = fitTransform(layout.bounds(), areaW, areaH);

      Graphics2D gg = (Graphics2D) g.create();
      gg.transform(lastTransform);
      paintLayout(gg, layout);
      gg.dispose();
    } finally {
      g.dispose();
    }
  }

  /** Centraliza a bounding box {@code b} (coords de mundo) na área, encolhendo só se não couber. */
  private AffineTransform fitTransform(Rectangle b, int areaW, int areaH) {
    int pad = 8;
    double scale =
        Math.min(
            1.0,
            Math.min((double) areaW / (b.width + 2 * pad), (double) areaH / (b.height + 2 * pad)));
    double worldCx = b.x + b.width / 2.0;
    double worldCy = b.y + b.height / 2.0;
    double viewCx = INSET + areaW / 2.0;
    double viewCy = INSET + areaH / 2.0;
    AffineTransform tx = new AffineTransform();
    tx.translate(viewCx, viewCy);
    tx.scale(scale, scale);
    tx.translate(-worldCx, -worldCy);
    return tx;
  }

  private void paintLayout(Graphics2D g, BoardGeometry.BoardLayout layout) {
    for (BoardGeometry.PlacedTile pt : layout.tiles()) {
      boolean isLast = lastPlaced != null && lastPlaced.equals(pt.tile());
      if (isLast) {
        g.setColor(LAST_MOVE);
        g.setStroke(new BasicStroke(4f));
        int m = 3;
        g.drawRoundRect(
            pt.bounds().x - m,
            pt.bounds().y - m,
            pt.bounds().width + 2 * m,
            pt.bounds().height + 2 * m,
            12,
            12);
      }
      TilePainter.paint(g, pt.bounds(), pt.orientation(), pt.nearPip(), pt.farPip(), false);
    }
    paintFitEnd(g, layout.left(), End.LEFT);
    paintFitEnd(g, layout.right(), End.RIGHT);
  }

  private void paintFitEnd(Graphics2D g, BoardGeometry.EndAnchor anchor, End end) {
    if (!interactive || hoverCandidate == null || !line.fits(hoverCandidate, end)) {
      return;
    }
    Rectangle h = anchor.hitbox();
    g.setColor(FIT_END);
    g.setStroke(
        new BasicStroke(
            3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {6f, 5f}, 0f));
    g.drawRoundRect(h.x, h.y, h.width, h.height, 10, 10);
  }

  private void paintOpeningHint(Graphics2D g) {
    int w = 140;
    int h = 72;
    int freeH = getHeight() - 2 * INSET - bottomReserve;
    int x = (getWidth() - w) / 2;
    int y = INSET + (freeH - h) / 2;
    g.setColor(HINT);
    g.setStroke(
        new BasicStroke(
            2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {8f, 6f}, 0f));
    g.drawRoundRect(x, y, w, h, 16, 16);
    g.setFont(getFont().deriveFont(Font.BOLD, 13f));
    String text = openingTile != null ? "abrir com " + openingTile : "mesa vazia";
    int tw = g.getFontMetrics().stringWidth(text);
    g.drawString(text, (getWidth() - tw) / 2, y + h + 22);
  }
}
