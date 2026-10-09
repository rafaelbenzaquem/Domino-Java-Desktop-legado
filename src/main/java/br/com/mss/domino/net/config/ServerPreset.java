package br.com.mss.domino.net.config;

/**
 * Um servidor conhecido em {@code servers.json} (ADR-0022). {@code isDefault} é o servidor sugerido
 * quando nada mais (CLI, escolha manual salva) decidiu por outro.
 *
 * <p>{@code tls} (M6-02, ADR-0030) diz se a conexão usa {@code TlsChannelCredentials} com validação
 * de certificado obrigatória — o servidor oficial atrás do Caddy. {@code false} é texto puro, como
 * antes: servidor embarcado (ADR-0020), descoberta em LAN (ADR-0021), {@code --server=} e "Endereço
 * personalizado…" nunca usam TLS.
 *
 * <p>{@code official} é campo próprio, não inferido do {@code name} (modelo do TchowStrick): só
 * vale para o catálogo embutido no {@code .jar} e sempre com {@code tls} — {@link ServerDirectory}
 * zera o campo em arquivo externo e em preset sem TLS.
 *
 * <p>{@code identity} (M1 deste repositório, Domino:M7) é opcional: presente, o jogador entra com a
 * conta MSS nesse destino e as chamadas de jogo levam {@code authorization: Bearer} com o acesso de
 * jogo da audiência {@code domino}. {@code null} mantém o comportamento anterior (LAN, embarcado,
 * servidores sem conta).
 */
public record ServerPreset(
    String name,
    String host,
    int port,
    boolean isDefault,
    boolean tls,
    boolean official,
    IdentityTarget identity) {

  /** Servidor sem identidade MSS (comportamento anterior ao M1). */
  public ServerPreset(
      String name, String host, int port, boolean isDefault, boolean tls, boolean official) {
    this(name, host, port, isDefault, tls, official, null);
  }

  /**
   * Preset em texto puro, não oficial e sem conta — o caso de LAN, embarcado e endereço digitado.
   */
  public ServerPreset(String name, String host, int port, boolean isDefault) {
    this(name, host, port, isDefault, false, false, null);
  }

  /** {@code true} se este servidor usa a identidade MSS para as credenciais de jogo. */
  public boolean usesMssIdentity() {
    return identity != null;
  }

  /** Mesmo servidor (host, porta e TLS), ignorando nome e marcação de padrão. */
  public boolean sameEndpoint(ServerPreset other) {
    return other != null
        && host.equalsIgnoreCase(other.host())
        && port == other.port()
        && tls == other.tls();
  }
}
