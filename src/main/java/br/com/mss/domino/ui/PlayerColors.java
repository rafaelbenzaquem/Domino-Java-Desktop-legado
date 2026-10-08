package br.com.mss.domino.ui;

import br.com.mss.domino.domain.Seat;
import br.com.mss.domino.domain.Team;
import java.awt.Color;

/** Cor associada a cada assento e a cada dupla. */
public final class PlayerColors {

  private static final Color[] BY_SEAT = {
    new Color(0xD1, 0x49, 0x3F), // vermelho
    new Color(0x2F, 0x6F, 0xB0), // azul
    new Color(0x3F, 0x9E, 0x5B), // verde
    new Color(0xC9, 0x8A, 0x2B), // âmbar
  };

  private PlayerColors() {}

  public static Color of(Seat seat) {
    return BY_SEAT[Math.floorMod(seat.index(), BY_SEAT.length)];
  }

  public static Color of(Team team) {
    return team == Team.ODD ? BY_SEAT[0] : BY_SEAT[1];
  }
}
