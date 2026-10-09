package br.com.mss.domino.app;

import java.util.Optional;

/**
 * Guarda, por partida e assento, o {@code session_token} emitido pelo host (ADR-0008, ADR-0013) —
 * usado para retomar a partida sozinho ao reabrir o cliente (ADR-0019), sem o jogador precisar
 * copiar/colar nada. A implementação atual ({@link LocalSessionTokenStore#forServer}) é local ao
 * dispositivo e já escopada a um servidor (BUG-009): ids de partida ({@code m1}…) se repetem entre
 * servidores e a cada subida de um servidor em memória. Se o armazenamento se perder (reinstalar,
 * trocar de máquina), a retomada automática daquele assento deixa de funcionar — o campo "Token de
 * sessão (opcional)" da {@code ConnectCard} continua como caminho avançado/manual para esse caso.
 */
public interface SessionTokenStore {

  /** O token guardado para {@code (matchId, seat)}, se algum join/reconnect anterior o recebeu. */
  Optional<String> find(String matchId, int seat);

  /** Guarda/substitui o token de {@code (matchId, seat)}. */
  void save(String matchId, int seat, String token);

  /** Descarta o token de {@code (matchId, seat)} — o host já não conhece essa sessão (BUG-009). */
  void remove(String matchId, int seat);
}
