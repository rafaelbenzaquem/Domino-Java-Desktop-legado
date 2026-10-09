package br.com.mss.domino.ui;

import br.com.mss.domino.app.PlayerProfile;
import br.com.mss.domino.app.ProfileStore;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Escolhe (ou cria) o perfil local ativo (ADR-0018). Mostrado na primeira execução (nenhum perfil
 * ainda) e pelo botão "Trocar perfil…" da tela inicial.
 */
public final class ProfileDialog extends JDialog {

  private final transient ProfileStore store;
  private final JComboBox<PlayerProfile> combo = new JComboBox<>();
  private transient PlayerProfile result;

  public ProfileDialog(Window owner, ProfileStore store) {
    super(owner, "Perfil do jogador", ModalityType.APPLICATION_MODAL);
    this.store = store;
    buildUi();
    reload(store.active().orElse(null));
    pack();
    setResizable(false);
    setLocationRelativeTo(owner);
  }

  /** Abre o diálogo. Devolve o perfil escolhido, ou {@code null} se o usuário cancelou. */
  public PlayerProfile showDialog() {
    if (store.list().isEmpty()) {
      createProfile(); // primeira vez: não dá pra seguir sem um perfil
    }
    setVisible(true);
    return result;
  }

  private void buildUi() {
    combo.setRenderer(
        (list, value, index, sel, focus) -> new JLabel(value == null ? "—" : value.displayName()));

    JButton create = new JButton("Novo perfil…");
    create.addActionListener(e -> createProfile());

    JButton ok = new JButton("Usar este");
    ok.addActionListener(
        e -> {
          PlayerProfile chosen = (PlayerProfile) combo.getSelectedItem();
          if (chosen != null) {
            store.setActive(chosen.id());
            result = chosen;
          }
          dispose();
        });

    JButton cancel = new JButton("Cancelar");
    cancel.addActionListener(e -> dispose());

    JPanel top = new JPanel(new BorderLayout(6, 0));
    top.setBorder(new EmptyBorder(10, 10, 6, 10));
    top.add(new JLabel("Perfil:"), BorderLayout.WEST);
    top.add(combo, BorderLayout.CENTER);
    top.add(create, BorderLayout.EAST);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(ok);
    buttons.add(cancel);

    setLayout(new BorderLayout());
    add(top, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
  }

  private void createProfile() {
    String name =
        JOptionPane.showInputDialog(
            this, "Nome do novo perfil:", "Novo perfil", JOptionPane.PLAIN_MESSAGE);
    if (name == null || name.isBlank()) {
      return;
    }
    reload(store.create(name.strip()));
  }

  private void reload(PlayerProfile toSelect) {
    List<PlayerProfile> all = store.list();
    combo.setModel(new DefaultComboBoxModel<>(all.toArray(new PlayerProfile[0])));
    if (toSelect != null) {
      for (PlayerProfile p : all) {
        if (p.id().equals(toSelect.id())) {
          combo.setSelectedItem(p);
          break;
        }
      }
    }
  }
}
