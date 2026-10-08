package br.com.mss.domino.ui;

/**
 * Dimensões de desenho de uma peça, em pixels.
 *
 * @param longSide lado maior (comprimento da peça deitada)
 * @param shortSide lado menor (uma face)
 * @param gap folga entre peças na cadeia
 */
public record TileMetrics(int longSide, int shortSide, int gap) {

  public TileMetrics {
    if (shortSide <= 0 || longSide < shortSide || gap < 0) {
      throw new IllegalArgumentException(
          "métricas inválidas: " + longSide + "/" + shortSide + "/" + gap);
    }
  }

  public static TileMetrics standard() {
    return new TileMetrics(56, 28, 2);
  }

  public TileMetrics scaled(double factor) {
    return new TileMetrics(
        Math.max(2, (int) Math.round(longSide * factor)),
        Math.max(1, (int) Math.round(shortSide * factor)),
        Math.max(0, (int) Math.round(gap * factor)));
  }
}
