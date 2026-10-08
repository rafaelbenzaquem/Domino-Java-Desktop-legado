package br.com.mss.domino.net.grpc;

import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.TlsChannelCredentials;
import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Credenciais do canal do cliente (M6-02, ADR-0030). Texto puro quando o servidor não pede TLS
 * (LAN, embarcado, {@code --server=}); com TLS, a validação de certificado é sempre obrigatória —
 * não existe "aceitar qualquer certificado".
 *
 * <p>Por padrão confia na cadeia da JVM (Let's Encrypt, no servidor oficial). Só para staging local
 * com certificado de desenvolvimento, a propriedade de sistema {@value #DEV_CA_PROPERTY} aponta um
 * arquivo PEM de CA; com ela, o cliente confia <b>somente</b> nessa CA, e avisa no log.
 */
final class ClientChannelSecurity {

  private static final Logger LOG = LoggerFactory.getLogger(ClientChannelSecurity.class);

  /** Caminho de um PEM de CA de desenvolvimento (ex.: a CA interna do Caddy do M6-03). */
  static final String DEV_CA_PROPERTY = "domino.tls.devCaFile";

  private ClientChannelSecurity() {}

  static String devCaFileFromSystem() {
    String value = System.getProperty(DEV_CA_PROPERTY);
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * @param devCaFile PEM de CA de desenvolvimento, ou {@code null} para a cadeia padrão da JVM;
   *     ignorado quando {@code tls} é falso
   * @throws IOException se {@code devCaFile} não puder ser lido como certificado
   */
  static ChannelCredentials credentials(boolean tls, String devCaFile) throws IOException {
    if (!tls) {
      return InsecureChannelCredentials.create();
    }
    if (devCaFile == null) {
      return TlsChannelCredentials.create();
    }
    File caFile = new File(devCaFile);
    // lê a CA antes de avisar: se o arquivo falhar, o log não pode dizer que confia nela
    ChannelCredentials credentials =
        TlsChannelCredentials.newBuilder().trustManager(caFile).build();
    LOG.warn(
        "TLS confiando SOMENTE na CA de desenvolvimento {} ({}) — uso de staging",
        caFile.getAbsolutePath(),
        DEV_CA_PROPERTY);
    return credentials;
  }
}
