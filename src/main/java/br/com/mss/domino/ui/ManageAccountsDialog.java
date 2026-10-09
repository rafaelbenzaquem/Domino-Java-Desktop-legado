package br.com.mss.domino.ui;

import br.com.mss.domino.app.LocalAccountsService.Entry;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/**
 * "Gerenciar contas…" (M1): contas MSS e perfis locais (janelas) guardados <b>neste computador</b>.
 * Só mostra e repassa as escolhas; quem abriu executa (entrar, nova janela, confirmação e remoção
 * local). Operações no servidor (sair de todos) continuam em "Conta MSS…".
 */
public final class ManageAccountsDialog extends JDialog {

  /** Ações executadas por quem abriu o painel; a tabela é recarregada depois de cada uma. */
  public interface Actions {
    List<Entry> list();

    void addMss();

    void addWindow();

    void remove(Entry entry);
  }

  private static final String[] COLUMNS = {
    "Tipo", "Nick/nome", "Identidade", "Conta", "Perfil local", "Estado", "Em uso"
  };

  private final transient Actions actions;
  private final EntriesModel model = new EntriesModel();
  private final JTable table = new JTable(model);
  private final JButton remove = new JButton("Remover deste computador…");

  public ManageAccountsDialog(Window owner, Actions actions) {
    super(owner, "Gerenciar contas neste computador", ModalityType.APPLICATION_MODAL);
    this.actions = actions;
    buildUi();
    reload();
    pack();
    setLocationRelativeTo(owner);
  }

  private void buildUi() {
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getSelectionModel().addListSelectionListener(e -> updateButtons());
    table.setPreferredScrollableViewportSize(new Dimension(820, 160));
    JScrollPane scroll = new JScrollPane(table);

    JButton addMss = new JButton("Entrar/criar conta MSS nesta janela…");
    addMss.addActionListener(e -> run(actions::addMss));
    JButton addWindow = new JButton("Nova janela (outro perfil local)…");
    addWindow.addActionListener(e -> run(actions::addWindow));
    remove.addActionListener(
        e -> {
          int row = table.getSelectedRow();
          if (row >= 0) {
            Entry entry = model.entries.get(row);
            run(() -> actions.remove(entry));
          }
        });
    JButton refresh = new JButton("Atualizar");
    refresh.addActionListener(e -> reload());
    JButton close = new JButton("Fechar");
    close.addActionListener(e -> dispose());

    JPanel add = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    add.add(addMss);
    add.add(addWindow);
    JPanel manage = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    manage.add(remove);
    manage.add(refresh);
    manage.add(close);
    JPanel south = new JPanel(new BorderLayout());
    south.add(add, BorderLayout.NORTH);
    south.add(manage, BorderLayout.SOUTH);

    JLabel hint =
        new JLabel(
            WrappedText.html(
                "Dados guardados só neste computador (nunca tokens). Cada janela aberta usa um"
                    + " perfil local, com a sua própria conta MSS. Remover apaga apenas os dados"
                    + " locais; sair de todos os dispositivos fica em Conta MSS….",
                760));
    JPanel content = new JPanel(new BorderLayout(6, 6));
    content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    content.add(hint, BorderLayout.NORTH);
    content.add(scroll, BorderLayout.CENTER);
    content.add(south, BorderLayout.SOUTH);
    setContentPane(content);
  }

  private void run(Runnable action) {
    action.run();
    reload();
  }

  private void reload() {
    model.entries = List.copyOf(actions.list());
    model.fireTableDataChanged();
    updateButtons();
  }

  private void updateButtons() {
    remove.setEnabled(table.getSelectedRow() >= 0);
  }

  private static final class EntriesModel extends AbstractTableModel {
    private List<Entry> entries = List.of();

    @Override
    public int getRowCount() {
      return entries.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
      Entry e = entries.get(row);
      return switch (column) {
        case 0 -> e.kind().label();
        case 1 -> e.name();
        case 2 -> e.destination();
        case 3 -> e.account();
        case 4 -> e.dataProfileLabel();
        case 5 -> e.state();
        default -> e.usage().label();
      };
    }
  }
}
