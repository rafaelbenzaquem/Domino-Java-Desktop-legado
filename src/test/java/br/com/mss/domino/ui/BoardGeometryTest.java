package br.com.mss.domino.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mss.domino.domain.End;
import br.com.mss.domino.domain.Line;
import br.com.mss.domino.domain.Pip;
import br.com.mss.domino.domain.Tile;
import br.com.mss.domino.ui.BoardGeometry.BoardLayout;
import br.com.mss.domino.ui.BoardGeometry.PlacedTile;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardGeometryTest {

  private final BoardGeometry geometry = BoardGeometry.standard();
  private final int gap = geometry.metrics().gap();
  private final int longSide = geometry.metrics().longSide();

  /** Cadeia reta encostada sempre à direita, começando no 0-0. */
  private static Line rightwardChain(int n) {
    Line line = Line.opening(Tile.of(0, 0));
    int right = 0;
    for (int i = 1; i < n; i++) {
      int next = (right + 1) % 7;
      line = line.place(Tile.of(right, next), End.RIGHT);
      right = next;
    }
    return line;
  }

  @Test
  void emptyBoardHasNoLayout() {
    BoardLayout layout = geometry.layout(Line.EMPTY, null);
    assertTrue(layout.isEmpty());
    assertNull(layout.left());
    assertNull(layout.right());
  }

  @Test
  void openingTileLiesFlatAtWorldOrigin() {
    PlacedTile pt = geometry.layout(Line.opening(Tile.of(3, 5)), null).tiles().get(0);
    assertEquals(Orientation.HORIZONTAL, pt.orientation());
    assertEquals(Pip.THREE, pt.nearPip());
    assertEquals(Pip.FIVE, pt.farPip());
    assertTrue(pt.bounds().contains(0, 0), "abertura centrada em (0,0): " + pt.bounds());
  }

  @Test
  void openingDoubleStandsUp() {
    PlacedTile pt = geometry.layout(Line.opening(Tile.of(6, 6)), null).tiles().get(0);
    assertEquals(Orientation.VERTICAL, pt.orientation());
  }

  @Test
  void endsCarryTheFreeValuesOfTheLine() {
    Line line =
        Line.opening(Tile.of(3, 5)).place(Tile.of(5, 2), End.RIGHT).place(Tile.of(1, 3), End.LEFT);
    BoardLayout layout = geometry.layout(line, null);
    assertEquals(End.LEFT, layout.left().end());
    assertEquals(line.leftEnd(), layout.left().value());
    assertEquals(End.RIGHT, layout.right().end());
    assertEquals(line.rightEnd(), layout.right().value());
  }

  @Test
  void singleGapAlongAStraightRun() {
    List<PlacedTile> tiles = geometry.layout(rightwardChain(4), null).tiles(); // curto: não dobra
    for (int i = 1; i < tiles.size(); i++) {
      Rectangle a = tiles.get(i - 1).bounds();
      Rectangle b = tiles.get(i).bounds();
      assertEquals(gap, b.x - (a.x + a.width), "gap entre peça " + (i - 1) + " e " + i);
    }
  }

  @Test
  void longChainFolds_withoutRunningAwayFromTheWorld() {
    BoardLayout layout = geometry.layout(rightwardChain(20), null);
    assertTrue(
        layout.bounds().height > longSide * 2, "esperava dobra: altura " + layout.bounds().height);
    assertTrue(
        layout.bounds().width <= 2 * BoardGeometry.WORLD_HALF_W + 4 * longSide,
        "largura fora de controle: " + layout.bounds().width);
  }

  @Test
  void everyJointShowsTheSharedPip() {
    List<PlacedTile> tiles = geometry.layout(rightwardChain(22), null).tiles();
    for (int i = 1; i < tiles.size(); i++) {
      PlacedTile prev = tiles.get(i - 1);
      PlacedTile cur = tiles.get(i);
      Pip shared = sharedPip(prev.tile(), cur.tile());
      Pip facing = halfFacing(cur, centerOf(prev.bounds()));
      assertEquals(
          shared,
          facing,
          "peça "
              + i
              + " ("
              + cur.tile()
              + ", dir "
              + cur.dir()
              + ") não encara "
              + prev.tile()
              + " com a face "
              + shared);
    }
  }

  @Test
  void aDoubleIsNeverTheCornerTile() {
    List<PlacedTile> tiles = geometry.layout(rightwardChain(24), null).tiles();
    assertTrue(tiles.stream().noneMatch(p -> p.corner() && p.tile().isDouble()));
    assertTrue(tiles.stream().anyMatch(PlacedTile::corner), "a cadeia deveria ter curva");
  }

  @Test
  void noTwoTilesCollide() {
    List<PlacedTile> tiles = geometry.layout(rightwardChain(24), null).tiles();
    for (int i = 0; i < tiles.size(); i++) {
      for (int j = i + 1; j < tiles.size(); j++) {
        Rectangle a = tiles.get(i).bounds();
        Rectangle b = new Rectangle(tiles.get(j).bounds());
        b.grow(-1, -1); // tolera encostar
        assertFalse(
            a.intersects(b),
            "colisão entre peça " + i + " e " + j + ": " + a + " x " + tiles.get(j).bounds());
      }
    }
  }

  @Test
  void cornerAlignsWithTheExposedHalfOfThePreviousTile() {
    List<PlacedTile> tiles = geometry.layout(rightwardChain(20), null).tiles();
    int cornerIdx = -1;
    for (int i = 0; i < tiles.size(); i++) {
      if (tiles.get(i).corner()) {
        cornerIdx = i;
        break;
      }
    }
    assertTrue(cornerIdx > 0, "sem curva");
    PlacedTile prev = tiles.get(cornerIdx - 1);
    PlacedTile corner = tiles.get(cornerIdx);
    int expectedAxisX = centerOf(prev.bounds()).x + longSide / 4; // prev normal correndo p/ direita
    assertTrue(
        Math.abs(centerOf(corner.bounds()).x - expectedAxisX) <= 2,
        "curva desalinhada: eixo " + centerOf(corner.bounds()).x + " esperado ~" + expectedAxisX);
  }

  @Test
  void layoutIsDeterministic() {
    Line line = rightwardChain(16);
    assertEquals(geometry.layout(line, null).bounds(), geometry.layout(line, null).bounds());
  }

  @Test
  void faceContinuityAlongAStraightRun() {
    List<PlacedTile> tiles = geometry.layout(rightwardChain(4), null).tiles();
    assertSame(Line.opening(Tile.of(0, 0)).leftEnd(), tiles.get(0).nearPip());
    for (int i = 1; i < tiles.size(); i++) {
      assertEquals(tiles.get(i - 1).farPip(), tiles.get(i).nearPip());
    }
  }

  // --- helpers ---

  private static Pip sharedPip(Tile a, Tile b) {
    return b.has(a.low()) ? a.low() : a.high();
  }

  private static Point centerOf(Rectangle r) {
    return new Point(r.x + r.width / 2, r.y + r.height / 2);
  }

  /** A face da metade de {@code t} mais próxima do ponto {@code towards}. */
  private static Pip halfFacing(PlacedTile t, Point towards) {
    Point c = centerOf(t.bounds());
    boolean nearHalf =
        t.orientation() == Orientation.HORIZONTAL ? towards.x <= c.x : towards.y <= c.y;
    return nearHalf ? t.nearPip() : t.farPip();
  }
}
