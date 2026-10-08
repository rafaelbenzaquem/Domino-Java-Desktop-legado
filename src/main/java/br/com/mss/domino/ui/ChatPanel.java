package br.com.mss.domino.ui;

import br.com.mss.domino.net.dto.ChatDto;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Chat aberto a todos (ADR-0009), texto puro. Os logs de sistema chegam pelo mesmo canal e aparecem
 * num tom próprio (itálico, cor diferente, prefixo "» "). Fase 4-4: o auto-scroll só puxa pro fim
 * quando o jogador já estava lá — rolar pra cima pra reler o histórico não é mais interrompido a
 * cada linha nova.
 */
public final class ChatPanel extends JPanel {

  private static final Color SYSTEM = new Color(0x8A, 0x6D, 0x3B);
  private static final Color USER = new Color(0x22, 0x22, 0x22);

  /** Tolerância (px) pra considerar "já estava no fim" mesmo com um pixel ou dois de folga. */
  private static final int BOTTOM_SLACK = 24;

  /** Fase 4.5-3: nunca deixa a {@code JSplitPane} da tela de jogo espremer o chat abaixo disso. */
  private static final int MIN_WIDTH_PX = 200;

  private final JTextPane log = new JTextPane();
  private final JScrollPane logScroll;
  private final JTextField input = new JTextField();
  private transient Consumer<String> onSend = t -> {};

  public ChatPanel() {
    super(new BorderLayout(6, 6));
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    setMinimumSize(new Dimension(MIN_WIDTH_PX, 0));

    log.setEditable(false);
    log.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    logScroll = new JScrollPane(log);
    add(logScroll, BorderLayout.CENTER);

    JButton send = new JButton("Enviar");
    JPanel south = new JPanel(new BorderLayout(6, 0));
    south.add(input, BorderLayout.CENTER);
    south.add(send, BorderLayout.EAST);
    add(south, BorderLayout.SOUTH);

    send.addActionListener(this::fire);
    input.addActionListener(this::fire);
  }

  public void setOnSend(Consumer<String> onSend) {
    this.onSend = onSend;
  }

  /** Acrescenta uma linha vinda de um {@code ChatPosted}. */
  public void append(ChatDto chat) {
    boolean wasAtBottom = isScrolledToBottom();
    StyledDocument doc = log.getStyledDocument();
    SimpleAttributeSet style = new SimpleAttributeSet();
    if (chat.system()) {
      StyleConstants.setForeground(style, SYSTEM);
      StyleConstants.setItalic(style, true);
    } else {
      StyleConstants.setForeground(style, USER);
    }
    String line = chat.system() ? "» " + chat.text() : chat.from() + ": " + chat.text();
    try {
      doc.insertString(doc.getLength(), line + "\n", style);
    } catch (BadLocationException ignored) {
      // não deveria acontecer ao inserir no fim
    }
    if (wasAtBottom) {
      SwingUtilities.invokeLater(() -> log.setCaretPosition(doc.getLength()));
    }
  }

  /** {@code true} se o jogador já estava vendo o fim do chat antes desta linha chegar. */
  private boolean isScrolledToBottom() {
    JScrollBar bar = logScroll.getVerticalScrollBar();
    return bar == null
        || bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - BOTTOM_SLACK;
  }

  public void setEnabledInput(boolean enabled) {
    input.setEnabled(enabled);
  }

  private void fire(ActionEvent e) {
    String text = input.getText().trim();
    if (!text.isEmpty()) {
      onSend.accept(text);
      input.setText("");
    }
  }
}
