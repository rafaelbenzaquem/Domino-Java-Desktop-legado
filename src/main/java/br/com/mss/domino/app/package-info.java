/**
 * Camada de aplicação do cliente: cola entre a rede e a UI.
 *
 * <ul>
 *   <li>{@link br.com.mss.domino.app.MatchController} — único assinante da rede; entrega eventos na
 *       EDT (ADR-0010), mantém a {@link br.com.mss.domino.app.ClientGameView} e re-emite.
 *   <li>{@link br.com.mss.domino.app.ClientEventBus} — <i>Subject</i> do Observer (ADR-0016).
 *   <li>{@link br.com.mss.domino.app.ClientGameView} — projeção somente-leitura; nunca roda regra
 *       (ADR-0002).
 * </ul>
 *
 * <p>Regra de dependência: {@code app} importa {@code net} e {@code domain}; nunca {@code
 * net.grpc}.
 */
package br.com.mss.domino.app;
