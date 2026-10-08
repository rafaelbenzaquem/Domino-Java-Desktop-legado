package br.com.mss.domino.ui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

/**
 * Acima da mesa: de quem é a vez agora, o *countdown* dela e se já está gastando o banco
 * (ADR-0012). Estimado localmente a partir do {@code turnDeadline} do último {@code ClockStarted} —
 * o servidor é a verdade; aqui só se interpola entre um evento e o próximo. Informação de
 * <b>qualquer</b> jogador, nunca só a própria (isso é o {@link PersonalClockPanel}, abaixo da mão).
 * Fase 4-2: fundo em pílula colorida conforme a urgência, não só o texto.
 */
public final class TurnBanner extends JLabel {

  private static final Color NORMAL_FG = new Color(0x22, 0x22, 0x22);
  private static final Color LOW_FG = Color.WHITE;
  private static final Color BANK_FG = Color.WHITE;

  private static final Color NORMAL_BG = new Color(0xFF, 0xF3, 0xCD);
  private static final Color LOW_BG = new Color(0xB0, 0x30, 0x20);
  private static final Color BANK_BG = new Color(0x8A, 0x4B, 0x00);

  private volatile String nick = "";
  private volatile long deadline;
  private volatile int bankAtArm;

  public TurnBanner() {
    super(" ", SwingConstants.CENTER);
    setFont(getFont().deriveFont(Font.BOLD, 14f));
    setOpaque(true);
    setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
    new Timer(250, e -> refresh()).start();
  }

  /** Sem relógio ativo (fora de jogo, ou compra forçada em andamento). */
  public void clear() {
    deadline = 0;
    refresh();
  }

  public void update(String nick, long deadline, int bankAtArm) {
    this.nick = nick;
    this.deadline = deadline;
    this.bankAtArm = bankAtArm;
    refresh();
  }

  private void refresh() {
    if (deadline <= 0) {
      setText(" ");
      setBackground(getParent() == null ? getBackground() : getParent().getBackground());
      return;
    }
    long remaining = ClockMath.remainingSeconds(deadline);
    boolean usingBank = ClockMath.usingBank(remaining, bankAtArm);
    boolean low = remaining <= 5;
    StringBuilder text =
        new StringBuilder("Vez de ").append(nick).append(" — ").append(remaining).append(" s");
    if (usingBank) {
      text.append("  (usando o banco)");
    }
    setText(text.toString());
    setForeground(usingBank ? BANK_FG : (low ? LOW_FG : NORMAL_FG));
    setBackground(usingBank ? BANK_BG : (low ? LOW_BG : NORMAL_BG));
  }
}
