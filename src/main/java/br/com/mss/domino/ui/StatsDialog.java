package br.com.mss.domino.ui;

import br.com.mss.domino.app.MatchController;
import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.net.dto.PlayerStatsDto;
import br.com.mss.domino.net.dto.RankingEntryDto;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;

/**
 * Minhas estatísticas e ranking do modo selecionado (M5-5, ADR-0029) — consulta {@link
 * MatchController#getMyStats}/{@link MatchController#getRanking} fora da EDT ({@link SwingWorker},
 * mesmo padrão do {@link ServerPickerDialog}). Aberto de novo a cada clique em
 * "Estatísticas/Ranking…" — nunca guarda estado entre aberturas, então troca de perfil/servidor
 * nunca mostra número de um perfil/servidor anterior.
 */
public final class StatsDialog extends JDialog {

  private static final int RANKING_LIMIT = 20;

  private final MatchController controller;
  private final String guestId;

  private final JComboBox<GameMode> modeCombo = new JComboBox<>(GameMode.values());
  private final JLabel statusLabel = new JLabel(" ");
  private final Color statusDefaultColor = statusLabel.getForeground();
  private final JLabel playedLabel = new JLabel("—");
  private final JLabel wonLabel = new JLabel("—");
  private final JLabel lostLabel = new JLabel("—");
  private final JLabel drawnLabel = new JLabel("—");
  private final DefaultListModel<RankingEntryDto> rankingModel = new DefaultListModel<>();
  private final JList<RankingEntryDto> rankingList = new JList<>(rankingModel);
  private final JButton refreshButton = new JButton("Atualizar");
  private final JButton closeButton = new JButton("Fechar");

  /** {@code initialMode} pré-seleciona o combo — útil para abrir já no modo da partida atual. */
  public StatsDialog(
      Window owner, MatchController controller, String guestId, GameMode initialMode) {
    super(owner, "Estatísticas e ranking", ModalityType.APPLICATION_MODAL);
    this.controller = controller;
    this.guestId = guestId;
    buildUi();
    modeCombo.setSelectedItem(initialMode);
    pack();
    setResizable(false);
    setLocationRelativeTo(owner);
  }

  /** Abre o diálogo (bloqueia) já carregando o modo pré-selecionado. */
  public void showDialog() {
    reload();
    setVisible(true);
  }

  private void buildUi() {
    modeCombo.setRenderer(
        (list, value, index, sel, focus) -> new JLabel(modeLabel((GameMode) value)));
    modeCombo.addActionListener(e -> reload());

    JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    modeRow.add(new JLabel("Modo:"));
    modeRow.add(modeCombo);

    JPanel statsPanel = new JPanel(new GridBagLayout());
    statsPanel.setBorder(BorderFactory.createTitledBorder("Minhas estatísticas"));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 6, 2, 6);
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.LINE_START;
    addStatRow(statsPanel, c, "Jogadas:", playedLabel);
    addStatRow(statsPanel, c, "Vitórias:", wonLabel);
    addStatRow(statsPanel, c, "Derrotas:", lostLabel);
    addStatRow(statsPanel, c, "Empates:", drawnLabel);

    rankingList.setCellRenderer(new RankingCellRenderer());
    JScrollPane rankingScroll = new JScrollPane(rankingList);
    // Mesma lição do ServerPickerDialog: tamanho fixo, senão o pack() inicial (lista vazia até a
    // 1ª consulta responder) deixa o diálogo minúsculo e ele não cresce sozinho depois.
    rankingScroll.setPreferredSize(new Dimension(360, 220));
    JPanel rankingPanel = new JPanel(new BorderLayout());
    rankingPanel.setBorder(BorderFactory.createTitledBorder("Ranking (top " + RANKING_LIMIT + ")"));
    rankingPanel.add(rankingScroll, BorderLayout.CENTER);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(refreshButton);
    buttons.add(closeButton);
    JPanel south = new JPanel(new BorderLayout());
    south.add(statusLabel, BorderLayout.NORTH);
    south.add(buttons, BorderLayout.SOUTH);

    refreshButton.addActionListener(e -> reload());
    closeButton.addActionListener(e -> dispose());

    JPanel center = new JPanel(new BorderLayout(8, 8));
    center.add(statsPanel, BorderLayout.NORTH);
    center.add(rankingPanel, BorderLayout.CENTER);

    JPanel content = new JPanel(new BorderLayout(8, 8));
    content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    content.add(modeRow, BorderLayout.NORTH);
    content.add(center, BorderLayout.CENTER);
    content.add(south, BorderLayout.SOUTH);

    setContentPane(content);
    getRootPane().setDefaultButton(closeButton);
  }

  private void addStatRow(JPanel panel, GridBagConstraints c, String label, JLabel value) {
    c.gridx = 0;
    panel.add(new JLabel(label), c);
    c.gridx = 1;
    panel.add(value, c);
    c.gridy++;
  }

  /** Consulta estatísticas e ranking do modo escolhido, fora da EDT — nunca trava a janela. */
  private void reload() {
    GameMode mode = (GameMode) modeCombo.getSelectedItem();
    setLoading(true);
    new SwingWorker<QueryResult, Void>() {
      @Override
      protected QueryResult doInBackground() {
        try {
          PlayerStatsDto stats = controller.getMyStats(guestId, mode);
          List<RankingEntryDto> ranking = controller.getRanking(mode, RANKING_LIMIT);
          return new QueryResult(stats, ranking, null);
        } catch (Exception e) {
          return new QueryResult(null, List.of(), e.getMessage());
        }
      }

      @Override
      protected void done() {
        setLoading(false);
        QueryResult result;
        try {
          result = get();
        } catch (Exception e) {
          result = new QueryResult(null, List.of(), e.getMessage());
        }
        if (result.error() != null) {
          showError(result.error());
        } else {
          renderStats(result.stats());
          renderRanking(result.ranking());
        }
      }
    }.execute();
  }

  private void setLoading(boolean loading) {
    modeCombo.setEnabled(!loading);
    refreshButton.setEnabled(!loading);
    if (loading) {
      statusLabel.setForeground(statusDefaultColor);
      statusLabel.setText("carregando…");
    }
  }

  private void showError(String message) {
    statusLabel.setForeground(Color.RED);
    statusLabel.setText("⚠ " + message);
    playedLabel.setText("—");
    wonLabel.setText("—");
    lostLabel.setText("—");
    drawnLabel.setText("—");
    rankingModel.clear();
  }

  private void renderStats(PlayerStatsDto stats) {
    playedLabel.setText(String.valueOf(stats.played()));
    wonLabel.setText(String.valueOf(stats.won()));
    lostLabel.setText(String.valueOf(stats.lost()));
    drawnLabel.setText(String.valueOf(stats.drawn()));
  }

  private void renderRanking(List<RankingEntryDto> ranking) {
    rankingModel.clear();
    ranking.forEach(rankingModel::addElement);
    statusLabel.setForeground(statusDefaultColor);
    statusLabel.setText(ranking.isEmpty() ? "nenhum jogador no ranking ainda" : " ");
  }

  private record QueryResult(PlayerStatsDto stats, List<RankingEntryDto> ranking, String error) {}

  private static String modeLabel(GameMode mode) {
    return mode == GameMode.PARTNERSHIP ? "Duplas" : "Um contra todos";
  }

  /** {@code "1. Ana — 3V 1D 0E (4 jogadas)"}. */
  private static final class RankingCellRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean hasFocus) {
      super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
      if (value instanceof RankingEntryDto e) {
        setText(
            "%d. %s — %dV %dD %dE (%d jogadas)"
                .formatted(
                    e.position(), e.displayName(), e.won(), e.lost(), e.drawn(), e.played()));
      }
      return this;
    }
  }
}
