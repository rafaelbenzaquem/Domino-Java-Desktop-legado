package br.com.mss.domino.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * Anel de contagem regressiva (Fase 4-2): a fatia preenchida é a fração do tempo restante, segundos
 * no centro. Sem estado próprio de tempo — só pinta o que {@link #update} manda; quem decide
 * "quanto falta" e chama de novo a cada tique é o painel dono ({@link PersonalClockPanel}).
 */
final class CircularCountdown extends JComponent {

  private static final Color TRACK = new Color(0xE2, 0xE2, 0xE2);
  private static final Color NORMAL = new Color(0x2E, 0x6B, 0x4F);
  private static final Color BANK = new Color(0x8A, 0x4B, 0x00);
  private static final Color LOW = new Color(0xB0, 0x30, 0x20);
  private static final int STROKE_WIDTH = 5;

  private int remaining = -1; // segundos; -1 = sem relógio ativo (some do desenho)
  private int total = 1;
  private boolean usingBank;

  CircularCountdown(int diameter) {
    setPreferredSize(new Dimension(diameter, diameter));
    setOpaque(false);
  }

  /** {@code total} é o denominador da fatia (normalmente os segundos por jogada, ADR-0012). */
  void update(int remaining, int total, boolean usingBank) {
    this.remaining = remaining;
    this.total = Math.max(total, 1);
    this.usingBank = usingBank;
    repaint();
  }

  /** Sem relógio ativo — desenha só o trilho, sem fatia nem número. */
  void clear() {
    remaining = -1;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int size = Math.min(getWidth(), getHeight()) - STROKE_WIDTH - 1;
    int x = (getWidth() - size) / 2;
    int y = (getHeight() - size) / 2;

    g2.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
    g2.setColor(TRACK);
    g2.drawOval(x, y, size, size);

    if (remaining >= 0) {
      double fraction = Math.max(0, Math.min(1, remaining / (double) total));
      g2.setColor(remaining <= 5 ? LOW : (usingBank ? BANK : NORMAL));
      g2.drawArc(x, y, size, size, 90, (int) Math.round(360 * fraction));

      String text = String.valueOf(remaining);
      g2.setFont(getFont().deriveFont(java.awt.Font.BOLD, size * 0.4f));
      FontMetrics fm = g2.getFontMetrics();
      int tx = getWidth() / 2 - fm.stringWidth(text) / 2;
      int ty = getHeight() / 2 + (fm.getAscent() - fm.getDescent()) / 2;
      g2.drawString(text, tx, ty);
    }
    g2.dispose();
  }
}
