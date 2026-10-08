package br.com.mss.domino.ui;

import br.com.mss.domino.domain.End;
import br.com.mss.domino.domain.Line;
import br.com.mss.domino.domain.Pip;
import br.com.mss.domino.domain.Tile;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layout puro e determinístico da cadeia de peças: {@code computeLayout(line, openingTile)}
 * devolve, para cada peça, o centro, a orientação e as faces já ordenadas para a pintura.
 * Recalculado do zero a cada jogada. Sem Swing de verdade (só {@link Rectangle}) e <b>sem tocar no
 * modelo</b>.
 *
 * <p>Coordenadas de <b>mundo</b>, com a peça de abertura no ponto {@code (0,0)} e limites virtuais
 * fixos ({@link #WORLD_HALF_W}/{@link #WORLD_HALF_H}) — as decisões de curva usam esses limites,
 * nunca o tamanho já escalado da tela. Quem centraliza/encolhe para caber na viewport é a {@link
 * BoardView}, com uma única transformação global.
 *
 * <p>Regras (ver o pedido de correção do layout):
 *
 * <ol>
 *   <li>orientação: eixo longo na direção do segmento; a face <b>inner</b> (a que encosta na peça
 *       anterior) sempre voltada para trás; carroça sempre perpendicular ao segmento e centrada no
 *       eixo;
 *   <li>reta: um único {@code GAP}; centro = âncora + dir · (GAP + comprimento/2);
 *   <li>curva: o novo eixo passa pelo centro da metade exposta da peça anterior;
 *   <li>carroça nunca é a peça de curva — uma peça normal só continua na direção atual se, depois
 *       dela, ainda couber uma carroça;
 *   <li>enquadramento: layout em mundo, uma transformação global depois.
 * </ol>
 */
public record BoardGeometry(TileMetrics metrics) {

  private static final Logger LOG = LoggerFactory.getLogger(BoardGeometry.class);

  /** Meia-largura/meia-altura do mundo virtual onde as curvas são decididas. */
  public static final int WORLD_HALF_W = 7 * (56 + 2);

  public static final int WORLD_HALF_H = 3 * (56 + 2);

  public static BoardGeometry standard() {
    return new BoardGeometry(TileMetrics.standard());
  }

  /** Direção em que a cadeia corre num trecho. */
  public enum RunDirection {
    RIGHT,
    DOWN,
    LEFT,
    UP;

    /** Próxima direção da curva (horário na tela): RIGHT→DOWN→LEFT→UP. */
    public RunDirection turnClockwise() {
      return values()[(ordinal() + 1) % 4];
    }

    public boolean horizontal() {
      return this == RIGHT || this == LEFT;
    }

    int dx() {
      return this == RIGHT ? 1 : this == LEFT ? -1 : 0;
    }

    int dy() {
      return this == DOWN ? 1 : this == UP ? -1 : 0;
    }
  }

  /**
   * Uma peça já posicionada.
   *
   * @param bounds retângulo de desenho, em coordenadas de mundo
   * @param orientation como a peça é desenhada (deitada/em pé)
   * @param nearPip face desenhada à esquerda (horizontal) ou no topo (vertical)
   * @param farPip face desenhada à direita ou embaixo
   * @param dir direção do segmento a que a peça pertence
   * @param corner esta peça é uma dobra?
   */
  public record PlacedTile(
      Tile tile,
      Rectangle bounds,
      Orientation orientation,
      Pip nearPip,
      Pip farPip,
      RunDirection dir,
      boolean corner) {}

  /** Uma ponta livre da cadeia: onde encostar a próxima peça. */
  public record EndAnchor(End end, Pip value, Rectangle hitbox, RunDirection outgoing) {}

  /**
   * Resultado do layout.
   *
   * @param tiles peças posicionadas, na ordem esquerda→direita do {@link Line}
   * @param left ponta esquerda (nulo se a mesa está vazia)
   * @param right ponta direita (nulo se a mesa está vazia)
   * @param bounds retângulo que envolve tudo (peças + pontas), em coordenadas de mundo
   */
  public record BoardLayout(
      List<PlacedTile> tiles, EndAnchor left, EndAnchor right, Rectangle bounds) {

    public boolean isEmpty() {
      return tiles.isEmpty();
    }
  }

  /** Compatibilidade com chamadas antigas: o tamanho da área é ignorado (ver Regra 5). */
  public BoardLayout layout(Line line, int ignoredWidth, int ignoredHeight) {
    return layout(line, null);
  }

  /** Compatibilidade com chamadas antigas. */
  public BoardLayout layout(Line line, int ignoredWidth, int ignoredHeight, Tile openingTile) {
    return layout(line, openingTile);
  }

  /**
   * Calcula o layout da {@code line}. A peça {@code openingTile} vai para o centro do mundo; se for
   * {@code null} usa a primeira peça da cadeia.
   */
  public BoardLayout layout(Line line, Tile openingTile) {
    if (line.isEmpty()) {
      return new BoardLayout(List.of(), null, null, new Rectangle(0, 0, 0, 0));
    }

    int longSide = metrics.longSide();
    int shortSide = metrics.shortSide();
    int gap = metrics.gap();

    List<Tile> tiles = line.tiles();
    int n = tiles.size();

    // faces encadeadas: near[i] encosta em i-1, far[i] em i+1.
    Pip[] near = new Pip[n];
    Pip[] far = new Pip[n];
    Pip prev = line.leftEnd();
    for (int i = 0; i < n; i++) {
      near[i] = prev;
      far[i] = tiles.get(i).other(prev);
      prev = far[i];
    }

    int center = openingTile == null ? 0 : Math.max(0, tiles.indexOf(openingTile));

    PlacedTile[] out = new PlacedTile[n];

    // Peça de abertura no centro do mundo.
    Tile centerTile = tiles.get(center);
    boolean centerDouble = centerTile.isDouble();
    int cw = centerDouble ? shortSide : longSide;
    int ch = centerDouble ? longSide : shortSide;
    Rectangle centerBounds = new Rectangle(-cw / 2, -ch / 2, cw, ch);
    out[center] =
        new PlacedTile(
            centerTile,
            centerBounds,
            centerDouble ? Orientation.VERTICAL : Orientation.HORIZONTAL,
            near[center],
            far[center],
            RunDirection.RIGHT,
            false);

    List<Rectangle> occupied = new ArrayList<>();
    occupied.add(centerBounds);

    RunDirection endRight =
        layArm(out, tiles, near, far, center, +1, n, RunDirection.RIGHT, occupied);
    RunDirection endLeft =
        layArm(out, tiles, near, far, center, -1, -1, RunDirection.LEFT, occupied);

    List<PlacedTile> placed = List.of(out);
    EndAnchor leftAnchor = endAnchor(End.LEFT, out[0], line.leftEnd(), endLeft);
    EndAnchor rightAnchor = endAnchor(End.RIGHT, out[n - 1], line.rightEnd(), endRight);

    Rectangle bounds = new Rectangle(centerBounds);
    for (PlacedTile p : placed) {
      bounds = bounds.union(p.bounds());
    }
    bounds = bounds.union(leftAnchor.hitbox()).union(rightAnchor.hitbox());
    return new BoardLayout(placed, leftAnchor, rightAnchor, bounds);
  }

  /**
   * Posiciona um braço a partir da peça de abertura. Percorre {@code from+step ..= end}
   * (exclusivo), mantendo uma reta e virando quando não couber mais uma peça normal + uma carroça
   * de reserva. Devolve a direção em que o braço terminou.
   */
  private RunDirection layArm(
      PlacedTile[] out,
      List<Tile> tiles,
      Pip[] near,
      Pip[] far,
      int from,
      int step,
      int end,
      RunDirection dir,
      List<Rectangle> occupied) {
    int longSide = metrics.longSide();
    int shortSide = metrics.shortSide();
    int gap = metrics.gap();

    PlacedTile previous = out[from];
    // âncora = ponto no eixo do segmento, no meio da borda exposta da peça anterior.
    int[] anchor = exposedEdgeMidpoint(previous, dir);
    // eixo perpendicular fixo do segmento atual
    int axis = dir.horizontal() ? anchor[1] : anchor[0];
    int turns = 0;

    for (int i = from + step; i != end; i += step) {
      Tile tile = tiles.get(i);
      boolean isDouble = tile.isDouble();
      int runLen = isDouble ? shortSide : longSide;

      // Reserva (Regra 4): peça normal só continua se, depois dela, ainda couber uma carroça.
      int need = isDouble ? (gap + shortSide) : (gap + longSide) + (gap + shortSide);
      boolean mustTurn = !isDouble && turns < 3 && spaceAhead(dir, anchor) < need;

      RunDirection tileDir = dir;
      boolean corner = false;
      int cx;
      int cy;

      if (mustTurn) {
        RunDirection newDir = dir.turnClockwise();
        int[] c = cornerCenter(previous, dir, newDir);
        cx = c[0];
        cy = c[1];
        tileDir = newDir;
        corner = true;
        turns++;
        // novo segmento
        dir = newDir;
        axis = dir.horizontal() ? cy : cx;
        // âncora = borda externa da peça de curva ao longo da nova direção (curva é normal → L)
        anchor = new int[] {cx + dir.dx() * (longSide / 2), cy + dir.dy() * (longSide / 2)};
      } else {
        if (dir.horizontal()) {
          cx = anchor[0] + dir.dx() * (gap + runLen / 2);
          cy = axis;
        } else {
          cx = axis;
          cy = anchor[1] + dir.dy() * (gap + runLen / 2);
        }
        // nova âncora = borda externa desta peça; a folga para a próxima entra no passo dela.
        anchor = new int[] {cx + dir.dx() * (runLen / 2), cy + dir.dy() * (runLen / 2)};
      }

      Orientation orientation = orientationFor(tileDir, isDouble);
      Rectangle bounds = rectAround(cx, cy, tileDir, isDouble);

      // inner encosta na peça anterior no percurso; outer é a exposta.
      Pip innerPip = step > 0 ? near[i] : far[i];
      Pip outerPip = step > 0 ? far[i] : near[i];
      Pip drawNear;
      Pip drawFar;
      if (tileDir == RunDirection.RIGHT || tileDir == RunDirection.DOWN) {
        drawNear = innerPip; // inner à esquerda / no topo
        drawFar = outerPip;
      } else {
        drawNear = outerPip; // inner à direita / embaixo
        drawFar = innerPip;
      }

      PlacedTile p = new PlacedTile(tile, bounds, orientation, drawNear, drawFar, tileDir, corner);
      out[i] = p;
      checkCollision(p, occupied);
      occupied.add(bounds);
      previous = p;
    }
    return dir;
  }

  /** Centro da metade exposta da peça anterior + início da peça de curva (Regra 3). */
  private int[] cornerCenter(PlacedTile p, RunDirection oldDir, RunDirection newDir) {
    int longSide = metrics.longSide();
    int shortSide = metrics.shortSide();
    int gap = metrics.gap();
    int px = p.bounds().x + p.bounds().width / 2;
    int py = p.bounds().y + p.bounds().height / 2;
    boolean pDouble = p.tile().isDouble();

    if (oldDir.horizontal()) {
      // horizontal → vertical
      int eixoX = pDouble ? px : px + oldDir.dx() * (longSide / 4);
      int meiaAlturaP = pDouble ? longSide / 2 : shortSide / 2;
      int cy =
          newDir == RunDirection.DOWN
              ? py + meiaAlturaP + gap + longSide / 2
              : py - meiaAlturaP - gap - longSide / 2;
      return new int[] {eixoX, cy};
    } else {
      // vertical → horizontal
      int eixoY = pDouble ? py : py + oldDir.dy() * (longSide / 4);
      int meiaLarguraP = pDouble ? longSide / 2 : shortSide / 2;
      int cx =
          newDir == RunDirection.RIGHT
              ? px + meiaLarguraP + gap + longSide / 2
              : px - meiaLarguraP - gap - longSide / 2;
      return new int[] {cx, eixoY};
    }
  }

  /** Ponto no meio da borda de {@code p} voltada para {@code dir}. */
  private int[] exposedEdgeMidpoint(PlacedTile p, RunDirection dir) {
    Rectangle b = p.bounds();
    int cx = b.x + b.width / 2;
    int cy = b.y + b.height / 2;
    return switch (dir) {
      case RIGHT -> new int[] {b.x + b.width, cy};
      case LEFT -> new int[] {b.x, cy};
      case DOWN -> new int[] {cx, b.y + b.height};
      case UP -> new int[] {cx, b.y};
    };
  }

  private int spaceAhead(RunDirection dir, int[] anchor) {
    return switch (dir) {
      case RIGHT -> WORLD_HALF_W - anchor[0];
      case LEFT -> anchor[0] + WORLD_HALF_W;
      case DOWN -> WORLD_HALF_H - anchor[1];
      case UP -> anchor[1] + WORLD_HALF_H;
    };
  }

  private static Orientation orientationFor(RunDirection dir, boolean isDouble) {
    boolean lyingAlong = dir.horizontal();
    // peça normal: eixo longo ao longo de dir; carroça: perpendicular.
    if (isDouble) {
      lyingAlong = !lyingAlong;
    }
    return lyingAlong ? Orientation.HORIZONTAL : Orientation.VERTICAL;
  }

  private Rectangle rectAround(int cx, int cy, RunDirection dir, boolean isDouble) {
    int longSide = metrics.longSide();
    int shortSide = metrics.shortSide();
    int runLen = isDouble ? shortSide : longSide;
    int crossLen = isDouble ? longSide : shortSide;
    int w = dir.horizontal() ? runLen : crossLen;
    int h = dir.horizontal() ? crossLen : runLen;
    return new Rectangle(cx - w / 2, cy - h / 2, w, h);
  }

  private EndAnchor endAnchor(End end, PlacedTile last, Pip value, RunDirection outgoing) {
    int longSide = metrics.longSide();
    int shortSide = metrics.shortSide();
    int gap = metrics.gap();
    int[] edge = exposedEdgeMidpoint(last, outgoing);
    int cx = edge[0] + outgoing.dx() * (gap + longSide / 2);
    int cy = edge[1] + outgoing.dy() * (gap + longSide / 2);
    int w = outgoing.horizontal() ? longSide : shortSide;
    int h = outgoing.horizontal() ? shortSide : longSide;
    Rectangle hit = new Rectangle(cx - w / 2, cy - h / 2, w, h);
    return new EndAnchor(end, value, hit, outgoing);
  }

  private void checkCollision(PlacedTile p, List<Rectangle> occupied) {
    int gap = metrics.gap();
    Rectangle grown =
        new Rectangle(
            p.bounds().x - gap,
            p.bounds().y - gap,
            p.bounds().width + 2 * gap,
            p.bounds().height + 2 * gap);
    for (Rectangle other : occupied) {
      if (grown.intersects(other)) {
        LOG.error("COLISÃO de layout: peça {} em {} sobre {}", p.tile(), p.bounds(), other);
        return;
      }
    }
  }
}
