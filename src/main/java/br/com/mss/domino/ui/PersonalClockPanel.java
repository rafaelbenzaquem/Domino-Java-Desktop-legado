package br.com.mss.domino.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.Timer;

/**
 * Abaixo da mão do jogador: informação <b>própria</b> — o banco de tempo que ele tem (mesmo fora da
 * sua vez, ADR-0012), numa barra que fica vermelha quando está acabando, e, só quando é a vez dele,
 * o *countdown* circular da jogada (Fase 4-2). O relógio "de quem está na vez" (para qualquer
 * jogador, não só você) é o {@link TurnBanner}, acima da mesa.
 */
public final class PersonalClockPanel extends JPanel {

  private static final Color BANK_LOW = new Color(0xB0, 0x30, 0x20);
  private static final Color BANK_NORMAL = new Color(0x2E, 0x6B, 0x4F);

  /** Fração do banco original abaixo da qual a barra vira vermelha e avisa "acabando". */
  private static final double LOW_BANK_FRACTION = 0.2;

  private final JProgressBar bankBar = new JProgressBar(0, 1);
  private final JLabel bankLabel = new JLabel(" ", SwingConstants.CENTER);
  private final CircularCountdown ring = new CircularCountdown(40);
  private final JLabel ringCaption = new JLabel(" ", SwingConstants.CENTER);

  private volatile int myBank = -1;
  private volatile int maxBankSeen = 0; // o banco só desce (ADR-0012) — o 1º valor visto é o teto
  private volatile long deadline; // só > 0 quando é a minha vez
  private volatile int bankAtArm;

  // teto do anel dentro de um ciclo de vez: sem turnSeconds explícito por aqui, o 1º "remaining"
  // lido depois que o deadline muda vira o teto (mesmo truque do maxBankSeen, aplicado por vez).
  private long lastDeadlineSeen = -1;
  private long totalAtArmStart = 1;

  public PersonalClockPanel() {
    super(new BorderLayout(8, 2));
    setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

    bankBar.setStringPainted(false);
    JPanel bankArea = new JPanel(new BorderLayout(0, 1));
    bankLabel.setFont(bankLabel.getFont().deriveFont(Font.PLAIN, 11f));
    bankArea.add(bankLabel, BorderLayout.NORTH);
    bankArea.add(bankBar, BorderLayout.CENTER);

    JPanel ringArea = new JPanel(new BorderLayout());
    ringCaption.setFont(ringCaption.getFont().deriveFont(Font.PLAIN, 10f));
    ringArea.add(ring, BorderLayout.CENTER);
    ringArea.add(ringCaption, BorderLayout.SOUTH);
    ring.setVisible(false);
    ringCaption.setVisible(false);

    add(bankArea, BorderLayout.CENTER);
    add(ringArea, BorderLayout.EAST);

    new Timer(250, e -> refresh()).start();
  }

  /**
   * @param myBank meu banco conhecido (segundos), ou {@code -1} se ainda não apareceu (nunca foi a
   *     minha vez ainda)
   * @param myTurn é a minha vez agora?
   * @param deadline o {@code deadline} da vez atual (só usado se {@code myTurn})
   * @param bankAtArm banco que eu tinha quando este relógio armou (só usado se {@code myTurn})
   */
  public void update(int myBank, boolean myTurn, long deadline, int bankAtArm) {
    this.myBank = myBank;
    if (myBank > maxBankSeen) {
      maxBankSeen = myBank; // primeira leitura da partida vira o teto da barra
    }
    this.deadline = myTurn ? deadline : 0;
    this.bankAtArm = bankAtArm;
    refresh();
  }

  private void refresh() {
    refreshBank();
    refreshRing();
  }

  private void refreshBank() {
    if (myBank < 0) {
      bankLabel.setText("Banco de tempo: —");
      bankBar.setEnabled(false);
      bankBar.setValue(0);
      return;
    }
    bankBar.setEnabled(true);
    int max = Math.max(maxBankSeen, 1);
    bankBar.setMaximum(max);
    bankBar.setValue(myBank);
    boolean low = maxBankSeen > 0 && myBank <= maxBankSeen * LOW_BANK_FRACTION;
    bankBar.setForeground(low ? BANK_LOW : BANK_NORMAL);
    bankLabel.setText(
        "Banco de tempo: " + myBank + " s" + (low && myBank > 0 ? "  ⚠ acabando" : ""));
  }

  private void refreshRing() {
    boolean active = deadline > 0;
    ring.setVisible(active);
    ringCaption.setVisible(active);
    if (!active) {
      ring.clear();
      lastDeadlineSeen = -1;
      return;
    }
    long remaining = ClockMath.remainingSeconds(deadline);
    boolean usingBank = ClockMath.usingBank(remaining, bankAtArm);
    if (deadline != lastDeadlineSeen) {
      lastDeadlineSeen = deadline;
      totalAtArmStart = Math.max(remaining, 1); // 1ª leitura deste ciclo = o teto do anel
    }
    int total = usingBank ? Math.max(bankAtArm, 1) : (int) totalAtArmStart;
    ring.update((int) remaining, total, usingBank);
    ringCaption.setText(usingBank ? "banco" : "sua vez");
  }
}
