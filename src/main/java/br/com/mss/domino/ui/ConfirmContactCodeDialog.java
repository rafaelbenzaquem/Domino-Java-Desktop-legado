package br.com.mss.domino.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Inserir o código enviado ao e-mail da conta MSS (M1) — para confirmar o contato ou entrar numa
 * conta existente. "Reenviar código" delega ao {@code onResend} de quem abriu o diálogo; esta
 * classe não fala com a rede.
 */
public final class ConfirmContactCodeDialog extends JDialog {

  public record Result(String code) {}

  private final JTextField codeField = new JTextField(10);
  private transient Result result;

  public ConfirmContactCodeDialog(Window owner, String email, Runnable onResend) {
    super(owner, "Confirmar e-mail", ModalityType.APPLICATION_MODAL);
    buildUi(email, onResend);
    pack();
    setResizable(false);
    setLocationRelativeTo(owner);
  }

  /** Abre o diálogo. {@code null} se o jogador cancelou (ou fechou sem confirmar). */
  public Result showDialog() {
    setVisible(true);
    return result;
  }

  private void buildUi(String email, Runnable onResend) {
    JLabel info =
        new JLabel(
            WrappedText.html(
                "Digite o código enviado para <b>"
                    + WrappedText.escape(email)
                    + "</b> (não chegou? Confira o spam ou peça outro):",
                WrappedText.WIDTH));

    JButton resend = new JButton("Reenviar código");
    resend.addActionListener(e -> onResend.run());
    JButton confirm = new JButton("Confirmar");
    confirm.addActionListener(e -> confirm());
    JButton cancel = new JButton("Cancelar");
    cancel.addActionListener(e -> dispose());

    JPanel top = new JPanel(new BorderLayout(6, 6));
    top.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
    top.add(info, BorderLayout.NORTH);
    JPanel field = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
    field.add(new JLabel("Código:"));
    field.add(codeField);
    field.add(resend);
    top.add(field, BorderLayout.CENTER);

    JPanel buttons = new JPanel();
    buttons.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
    buttons.add(confirm);
    buttons.add(cancel);

    setLayout(new BorderLayout());
    add(top, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(confirm);
  }

  private void confirm() {
    String code = codeField.getText().strip();
    if (code.isBlank()) {
      JOptionPane.showMessageDialog(
          this, "Informe o código.", "Código vazio", JOptionPane.WARNING_MESSAGE);
      return;
    }
    result = new Result(code);
    dispose();
  }
}
