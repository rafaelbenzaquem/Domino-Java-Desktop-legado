package br.com.mss.domino.ui;

import br.com.mss.domino.domain.Pip;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** Desenho vetorial de uma peça (retângulo arredondado + divisória + pips). Sem imagens. */
final class TilePainter {

  static final Color TILE_FILL = new Color(0xF4, 0xEF, 0xE3);
  static final Color TILE_EDGE = new Color(0x3A, 0x33, 0x2A);
  static final Color PIP = new Color(0x2A, 0x25, 0x1F);

  // Posições dos pips numa face, em coordenadas [0,1] (grade 3x3).
  private static final double[][] TL_C_BR = {{.25, .25}, {.5, .5}, {.75, .75}};

  private TilePainter() {}

  static void paint(
      Graphics2D g,
      Rectangle bounds,
      Orientation orientation,
      Pip near,
      Pip far,
      boolean highlight) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int arc = Math.max(6, Math.min(bounds.width, bounds.height) / 5);
    g.setColor(TILE_FILL);
    g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);

    if (highlight) {
      g.setColor(new Color(0x3F, 0x9E, 0x5B, 70));
      g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);
    }

    g.setColor(TILE_EDGE);
    g.setStroke(new BasicStroke(highlight ? 2.4f : 1.4f));
    g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);

    Rectangle nearHalf;
    Rectangle farHalf;
    if (orientation == Orientation.HORIZONTAL) {
      int half = bounds.width / 2;
      nearHalf = new Rectangle(bounds.x, bounds.y, half, bounds.height);
      farHalf = new Rectangle(bounds.x + half, bounds.y, bounds.width - half, bounds.height);
      g.drawLine(bounds.x + half, bounds.y + 3, bounds.x + half, bounds.y + bounds.height - 3);
    } else {
      int half = bounds.height / 2;
      nearHalf = new Rectangle(bounds.x, bounds.y, bounds.width, half);
      farHalf = new Rectangle(bounds.x, bounds.y + half, bounds.width, bounds.height - half);
      g.drawLine(bounds.x + 3, bounds.y + half, bounds.x + bounds.width - 3, bounds.y + half);
    }

    paintFace(g, nearHalf, near);
    paintFace(g, farHalf, far);
  }

  private static void paintFace(Graphics2D g, Rectangle r, Pip pip) {
    g.setColor(PIP);
    double d = Math.min(r.width, r.height) * 0.18;
    for (double[] p : pipPositions(pip.value())) {
      double px = r.x + p[0] * r.width - d / 2;
      double py = r.y + p[1] * r.height - d / 2;
      g.fillOval(
          (int) Math.round(px), (int) Math.round(py), (int) Math.round(d), (int) Math.round(d));
    }
  }

  private static double[][] pipPositions(int value) {
    double a = .25;
    double b = .5;
    double c = .75;
    return switch (value) {
      case 0 -> new double[][] {};
      case 1 -> new double[][] {{b, b}};
      case 2 -> new double[][] {{a, a}, {c, c}};
      case 3 -> TL_C_BR;
      case 4 -> new double[][] {{a, a}, {c, a}, {a, c}, {c, c}};
      case 5 -> new double[][] {{a, a}, {c, a}, {b, b}, {a, c}, {c, c}};
      case 6 -> new double[][] {{a, a}, {c, a}, {a, b}, {c, b}, {a, c}, {c, c}};
      default -> throw new IllegalArgumentException("pip inválido: " + value);
    };
  }
}
