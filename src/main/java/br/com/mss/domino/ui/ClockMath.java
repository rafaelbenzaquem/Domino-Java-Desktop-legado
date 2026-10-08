package br.com.mss.domino.ui;

/**
 * Conta regressiva do relógio de turno (ADR-0012), compartilhada por {@link TurnBanner} e {@link
 * PersonalClockPanel}. O banco só entra em uso depois que o tempo "grátis" ({@code turnSeconds})
 * acaba — como o {@code deadline} já embute os dois, isso equivale a: o tempo que falta é menor ou
 * igual ao banco que havia quando o relógio armou.
 */
final class ClockMath {

  private ClockMath() {}

  static long remainingSeconds(long deadlineMillis) {
    return Math.max(0, deadlineMillis - System.currentTimeMillis() + 999) / 1000;
  }

  static boolean usingBank(long remainingSeconds, int bankAtArm) {
    return bankAtArm > 0 && remainingSeconds <= bankAtArm;
  }
}
