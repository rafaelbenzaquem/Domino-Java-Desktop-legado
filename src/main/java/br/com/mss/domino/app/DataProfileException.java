package br.com.mss.domino.app;

/**
 * Falha ao abrir, travar ou remover um {@link DataProfile perfil local de dados} (M1). A mensagem é
 * para o jogador, em português.
 */
public final class DataProfileException extends RuntimeException {

  public DataProfileException(String message) {
    super(message);
  }
}
