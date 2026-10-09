package br.com.mss.domino.ui;

import java.awt.Component;
import java.awt.GridLayout;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * Entrar ou criar conta MSS com e-mail (M1). Conta nova pede nick + e-mail; conta existente só o
 * e-mail (um código de recuperação é enviado). O código vem depois, no {@link
 * ConfirmContactCodeDialog}. Esta classe não fala com a rede.
 */
public final class MssSignInDialog {

  /** {@code newAccount = false}: entrar numa conta existente. */
  public record Result(boolean newAccount, String nick, String email) {}

  private MssSignInDialog() {}

  /** {@code null} se o jogador cancelou. */
  public static Result show(Component owner, String serverName, String suggestedNick) {
    JRadioButton create = new JRadioButton("Criar conta nova", true);
    JRadioButton existing = new JRadioButton("Já tenho conta (entrar com código por e-mail)");
    ButtonGroup group = new ButtonGroup();
    group.add(create);
    group.add(existing);
    JTextField nick = new JTextField(suggestedNick == null ? "" : suggestedNick, 20);
    JTextField email = new JTextField(24);
    create.addActionListener(e -> nick.setEnabled(true));
    existing.addActionListener(e -> nick.setEnabled(false));

    JPanel form = new JPanel(new GridLayout(0, 1, 2, 2));
    form.add(WrappedText.label("Conta MSS para o servidor " + serverName));
    form.add(create);
    form.add(existing);
    form.add(new JLabel("Nick:"));
    form.add(nick);
    form.add(new JLabel("E-mail:"));
    form.add(email);
    form.add(new JLabel("Telefone ainda não é suportado no desktop."));

    while (true) {
      int ok =
          JOptionPane.showConfirmDialog(
              owner,
              form,
              "Entrar na conta MSS",
              JOptionPane.OK_CANCEL_OPTION,
              JOptionPane.PLAIN_MESSAGE);
      if (ok != JOptionPane.OK_OPTION) {
        return null;
      }
      String emailValue = email.getText().strip();
      String nickValue = nick.getText().strip();
      if (emailValue.isEmpty() || !emailValue.contains("@")) {
        JOptionPane.showMessageDialog(
            owner, "Informe um e-mail válido.", "E-mail", JOptionPane.WARNING_MESSAGE);
        continue;
      }
      if (create.isSelected() && nickValue.isEmpty()) {
        JOptionPane.showMessageDialog(
            owner, "Informe um nick.", "Nick", JOptionPane.WARNING_MESSAGE);
        continue;
      }
      return new Result(create.isSelected(), nickValue, emailValue);
    }
  }
}
