package br.com.mss.domino.ui;

import br.com.mss.domino.net.config.ServerDirectory;
import br.com.mss.domino.net.config.ServerPreset;
import br.com.mss.domino.net.discovery.DiscoveredServer;
import br.com.mss.domino.net.discovery.LanServerFinder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;

/**
 * Diálogo de troca de servidor (ADR-0022): lista os presets do {@code servers.json} e deixa buscar
 * mais na rede local (ADR-0021) sob pedido. "Endereço personalizado…" continua disponível para quem
 * precisa digitar um IP:porta específico, mas fica dentro deste diálogo — nunca é o caminho comum
 * da {@code ConnectCard}.
 */
public final class ServerPickerDialog extends JDialog {

  private final DefaultListModel<ServerPreset> model = new DefaultListModel<>();
  private final JList<ServerPreset> list = new JList<>(model);
  private final JButton searchButton = new JButton("Procurar na rede local");
  private final JButton okButton = new JButton("Usar este servidor");
  private final JLabel infoLabel = new JLabel(" ");

  private ServerPreset result;

  /** {@code current} pré-seleciona a linha correspondente na lista, se houver uma igual. */
  public ServerPickerDialog(Window owner, ServerPreset current) {
    super(owner, "Trocar servidor", ModalityType.APPLICATION_MODAL);
    buildUi();
    loadPresets(current);
    pack();
    setResizable(false);
    setLocationRelativeTo(owner);
  }

  /** Abre o diálogo (bloqueia) e devolve o servidor escolhido, ou {@code null} se cancelado. */
  public ServerPreset showDialog() {
    setVisible(true);
    return result;
  }

  private void loadPresets(ServerPreset current) {
    ServerDirectory.load().presets().forEach(model::addElement);
    selectMatching(current);
  }

  private void selectMatching(ServerPreset target) {
    if (target == null) {
      return;
    }
    // M1: o mesmo host:porta pode aparecer com e sem conta MSS ("Local (conta MSS)" e "Local
    // (sem conta)"); prefere o que bate também a identidade.
    int hostPortMatch = -1;
    for (int i = 0; i < model.size(); i++) {
      ServerPreset preset = model.get(i);
      if (preset.host().equals(target.host()) && preset.port() == target.port()) {
        if (Objects.equals(preset.identity(), target.identity())) {
          list.setSelectedIndex(i);
          return;
        }
        if (hostPortMatch < 0) {
          hostPortMatch = i;
        }
      }
    }
    if (hostPortMatch >= 0) {
      list.setSelectedIndex(hostPortMatch);
    }
  }

  private void buildUi() {
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new ServerCellRenderer());
    list.addListSelectionListener(e -> okButton.setEnabled(list.getSelectedValue() != null));

    JScrollPane scroll = new JScrollPane(list);
    // Tamanho fixo, independente de quantos servidores a busca em LAN acrescentar depois do
    // pack() — mesma lição de produção do TchowStrick (JScrollPane sem setPreferredSize some da
    // tela quando o modelo cresce depois do layout inicial).
    scroll.setPreferredSize(new Dimension(360, 120));

    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    top.add(searchButton);
    JButton customButton = new JButton("Endereço personalizado…");
    top.add(customButton);

    JPanel south = new JPanel(new BorderLayout(0, 4));
    south.add(infoLabel, BorderLayout.NORTH);
    JButton cancel = new JButton("Cancelar");
    JPanel buttons = new JPanel();
    buttons.add(okButton);
    buttons.add(cancel);
    south.add(buttons, BorderLayout.SOUTH);

    JPanel content = new JPanel(new BorderLayout(6, 6));
    content.add(top, BorderLayout.NORTH);
    content.add(scroll, BorderLayout.CENTER);
    content.add(south, BorderLayout.SOUTH);

    searchButton.addActionListener(e -> search());
    customButton.addActionListener(e -> chooseCustomAddress());
    okButton.addActionListener(e -> confirmSelection());
    cancel.addActionListener(e -> dispose());
    okButton.setEnabled(!model.isEmpty());

    setContentPane(content);
    getRootPane().setDefaultButton(okButton);
  }

  private void confirmSelection() {
    ServerPreset selected = list.getSelectedValue();
    if (selected == null) {
      return;
    }
    result = selected;
    dispose();
  }

  private void search() {
    searchButton.setEnabled(false);
    infoLabel.setText("procurando…");
    new SwingWorker<List<DiscoveredServer>, Void>() {
      @Override
      protected List<DiscoveredServer> doInBackground() {
        return LanServerFinder.find(Duration.ofSeconds(1));
      }

      @Override
      protected void done() {
        searchButton.setEnabled(true);
        List<DiscoveredServer> found;
        try {
          found = get();
        } catch (Exception ex) {
          found = List.of();
        }
        int added = appendNewOnes(found);
        infoLabel.setText(
            added == 0
                ? "nenhum servidor novo encontrado na rede local"
                : added + " servidor(es) encontrado(s) na rede local");
      }
    }.execute();
  }

  private int appendNewOnes(List<DiscoveredServer> found) {
    int added = 0;
    for (DiscoveredServer server : found) {
      if (!containsHostPort(server.host(), server.port())) {
        model.addElement(new ServerPreset(server.name(), server.host(), server.port(), false));
        added++;
      }
    }
    return added;
  }

  private boolean containsHostPort(String host, int port) {
    for (int i = 0; i < model.size(); i++) {
      ServerPreset preset = model.get(i);
      if (preset.host().equals(host) && preset.port() == port) {
        return true;
      }
    }
    return false;
  }

  private void chooseCustomAddress() {
    String input =
        JOptionPane.showInputDialog(
            this, "Endereço (host:porta):", "Endereço personalizado", JOptionPane.PLAIN_MESSAGE);
    if (input == null || input.isBlank()) {
      return;
    }
    int separator = input.lastIndexOf(':');
    if (separator <= 0 || separator == input.length() - 1) {
      warnInvalidAddress("Formato esperado: host:porta");
      return;
    }
    String host = input.substring(0, separator).strip();
    int port;
    try {
      port = Integer.parseInt(input.substring(separator + 1).strip());
    } catch (NumberFormatException e) {
      warnInvalidAddress("Porta inválida.");
      return;
    }
    result = new ServerPreset("personalizado", host, port, false);
    dispose();
  }

  private void warnInvalidAddress(String message) {
    JOptionPane.showMessageDialog(this, message, "Endereço inválido", JOptionPane.WARNING_MESSAGE);
  }

  /**
   * {@code "<nome> (<host>:<porta>)"} — [TLS] marca conexão cifrada (M6-02); "(conta MSS)", o
   * servidor que exige conta MSS (M1); ★, o preset padrão.
   */
  private static final class ServerCellRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean hasFocus) {
      super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
      if (value instanceof ServerPreset preset) {
        setText(
            "%s (%s:%d)%s%s%s"
                .formatted(
                    preset.name(),
                    preset.host(),
                    preset.port(),
                    preset.tls() ? " [TLS]" : "",
                    preset.usesMssIdentity() ? " (conta MSS)" : "",
                    preset.isDefault() ? "  ★" : ""));
      }
      return this;
    }
  }
}
