/**
 * Camada de apresentação (Swing). Componentes sempre tocados na EDT.
 *
 * <p>Nesta fase (Fase 2) só existe a renderização do tabuleiro sobre o modelo do {@code domain}:
 * {@link br.com.mss.domino.ui.BoardGeometry} (layout puro, testável sem tela — nunca muta o modelo)
 * e {@link br.com.mss.domino.ui.BoardView} / {@link br.com.mss.domino.ui.HandPanel} (pintura
 * vetorial com Graphics2D, sem imagens). Rede, lobby e Observer entram na Fase 3.
 *
 * <p>Depende de {@code domain}; nunca o contrário.
 */
package br.com.mss.domino.ui;
