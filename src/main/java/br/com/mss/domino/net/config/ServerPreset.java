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
 * zera o campo em arquivo externo e em preset sem TLS. Nada no M6 depende dele ainda; a conta MSS
 * (M7) vai.
 */
public record ServerPreset(
    String name, String host, int port, boolean isDefault, boolean tls, boolean official) {

  /** Preset em texto puro e não oficial — o caso de LAN, embarcado e endereço digitado. */
  public ServerPreset(String name, String host, int port, boolean isDefault) {
    this(name, host, port, isDefault, false, false);
  }
}
