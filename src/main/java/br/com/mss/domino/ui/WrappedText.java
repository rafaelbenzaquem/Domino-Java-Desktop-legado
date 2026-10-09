package br.com.mss.domino.ui;

import javax.swing.JLabel;

/**
 * Textos longos (mensagens da conta MSS, nomes de servidor) quebrando linha numa largura fixa em
 * vez de alargar o diálogo além da tela (lição do TchowStrick-Java-Desktop-Legado:BUG-001).
 */
final class WrappedText {

  /** Largura padrão, em pixels, do texto quebrado nos diálogos. */
  static final int WIDTH = 420;

  private WrappedText() {}

  /** {@code <html>} com largura fixa; {@code html} já precisa estar escapado. */
  static String html(String html, int width) {
    return "<html><div style='width:" + width + "px'>" + html + "</div></html>";
  }

  /** Rótulo com {@code text} (texto puro) escapado e quebrado em {@link #WIDTH}. */
  static JLabel label(String text) {
    return new JLabel(html(escape(text).replace("\n", "<br>"), WIDTH));
  }

  /** Mensagem para {@code JOptionPane}: texto puro quebrado em {@link #WIDTH}. */
  static Object message(String text) {
    return label(text);
  }

  static String escape(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
