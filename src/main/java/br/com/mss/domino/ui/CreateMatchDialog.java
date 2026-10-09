package br.com.mss.domino.ui;

import br.com.mss.domino.domain.GameMode;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;

/**
 * "Criar partida" (ADR-0014, Fase 4-1). Dois modos: "um contra todos" (2–4) e "duplas" (4 fixos),
 * relógio de turno (ADR-0012), e a visibilidade Aberta/Fechada (com senha) — a senha em si nunca
 * sai deste processo em claro, só o hash calculado no {@code GrpcClientTransport}.
 */
public final class CreateMatchDialog extends JDialog {

  /** Escolha do usuário. {@code password} vazia = partida aberta. */
  public record Result(
      String name,
      GameMode mode,
      int size,
      int turnSeconds,
      int timeBankSeconds,
      String password) {}

  private static final String FFA = "Um contra todos";
  private static final String PART = "Duplas (2 contra 2)";

  private final JTextField nameField = new JTextField(18);
  private final JComboBox<String> modeBox = new JComboBox<>(new String[] {FFA, PART});
  private final JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(4, 2, 4, 1));
  private final JSpinner turnSpinner = new JSpinner(new SpinnerNumberModel(30, 10, 120, 5));
  private final JSpinner bankSpinner = new JSpinner(new SpinnerNumberModel(120, 0, 600, 15));
  private final JRadioButton openButton = new JRadioButton("Aberta", true);
  private final JRadioButton lockedButton = new JRadioButton("Fechada (com senha)");
  private final JPasswordField passwordField = new JPasswordField(18);
  private Result result;

  public CreateMatchDialog(Window owner, String suggestedName) {
    super(owner, "Criar partida", ModalityType.APPLICATION_MODAL);
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    nameField.setText(suggestedName);

    ButtonGroup visibility = new ButtonGroup();
    visibility.add(openButton);
    visibility.add(lockedButton);
    JPanel visibilityRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    visibilityRow.add(openButton);
    visibilityRow.add(lockedButton);

    JPanel form = new JPanel(new GridLayout(7, 2, 8, 8));
    form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    form.add(new JLabel("Nome:"));
    form.add(nameField);
    form.add(new JLabel("Modo:"));
    form.add(modeBox);
    form.add(new JLabel("Jogadores:"));
    form.add(sizeSpinner);
    form.add(new JLabel("Segundos por jogada:"));
    form.add(turnSpinner);
    form.add(new JLabel("Banco de tempo (s, 0 = sem banco):"));
    form.add(bankSpinner);
    form.add(new JLabel("Visibilidade:"));
    form.add(visibilityRow);
    form.add(new JLabel("Senha:"));
    form.add(passwordField);

    modeBox.addActionListener(e -> syncSize());
    syncSize();
    openButton.addActionListener(e -> syncPasswordField());
    lockedButton.addActionListener(e -> syncPasswordField());
    syncPasswordField();

    JButton ok = new JButton("Criar");
    JButton cancel = new JButton("Cancelar");
    JPanel buttons = new JPanel();
    buttons.add(ok);
    buttons.add(cancel);

    ok.addActionListener(
        e -> {
          String name = nameField.getText().trim();
          if (!name.isEmpty()) {
            result =
                new Result(
                    name,
                    selectedMode(),
                    (Integer) sizeSpinner.getValue(),
                    (Integer) turnSpinner.getValue(),
                    (Integer) bankSpinner.getValue(),
                    lockedButton.isSelected() ? new String(passwordField.getPassword()) : "");
            dispose();
          }
        });
    cancel.addActionListener(e -> dispose());

    add(form, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    pack();
    setLocationRelativeTo(owner);
  }

  /** Campo de senha só faz sentido com "Fechada" selecionada. */
  private void syncPasswordField() {
    passwordField.setEnabled(lockedButton.isSelected());
  }

  private GameMode selectedMode() {
    return PART.equals(modeBox.getSelectedItem()) ? GameMode.PARTNERSHIP : GameMode.FREE_FOR_ALL;
  }

  private void syncSize() {
    if (selectedMode() == GameMode.PARTNERSHIP) {
      sizeSpinner.setValue(4);
      sizeSpinner.setEnabled(false);
    } else {
      sizeSpinner.setEnabled(true);
    }
  }

  /** Abre modal e devolve a escolha, ou vazio se cancelou. */
  public Optional<Result> prompt() {
    setVisible(true);
    return Optional.ofNullable(result);
  }
}
