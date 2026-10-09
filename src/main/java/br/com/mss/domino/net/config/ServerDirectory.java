package br.com.mss.domino.net.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lista de servidores conhecidos pelo cliente (ADR-0022) — resolve, em ordem:
 *
 * <ol>
 *   <li>um arquivo externo {@code servers.json} (caminho: propriedade de sistema {@value
 *       #EXTERNAL_FILE_PROPERTY}, senão {@value #DEFAULT_EXTERNAL_FILE} no diretório de trabalho) —
 *       se existir e for válido, <b>substitui</b> a lista inteira, sem mesclar com a embutida;
 *   <li>senão, o {@code servers.json} embutido no {@code .jar} ({@value #BUNDLED_RESOURCE}), que
 *       traz o preset "Local" ({@code localhost:1099}, padrão) e o "Oficial" ({@code
 *       domino.minashonsoftware.com.br:443}, TLS, com a identidade MSS de produção — Domino:M6-06,
 *       M1 deste repositório).
 * </ol>
 *
 * <p>{@code official} só é aceito do embutido e com {@code tls: true} (M6-02, ADR-0030): num
 * arquivo externo ele é ignorado, e um preset oficial sem TLS perde a marca, com aviso no log.
 *
 * <p>{@code identity} ({@code host:porta}) e {@code identityTls} (padrão {@code true}) são
 * opcionais (M1): com eles, o servidor exige conta MSS. Texto claro na identidade só para
 * localhost; um destino inválido invalida o arquivo inteiro (cai para o embutido, com aviso).
 *
 * <p>Nunca lança: um arquivo externo malformado só gera um aviso no log e cai para o embutido; se
 * até o embutido faltar/for inválido, a lista fica vazia (quem chama decide o que fazer — ver
 * {@link br.com.mss.domino.ConnectionResolver}).
 */
public final class ServerDirectory {

  private static final Logger LOG = LoggerFactory.getLogger(ServerDirectory.class);

  static final String EXTERNAL_FILE_PROPERTY = "domino.servers.file";
  static final String DEFAULT_EXTERNAL_FILE = "servers.json";
  private static final String BUNDLED_RESOURCE = "/servers-default.json";

  private final List<ServerPreset> presets;

  private ServerDirectory(List<ServerPreset> presets) {
    this.presets = List.copyOf(presets);
  }

  /** Constrói uma lista em memória, sem tocar arquivo/recurso nenhum — útil para teste. */
  public static ServerDirectory of(List<ServerPreset> presets) {
    return new ServerDirectory(presets);
  }

  public List<ServerPreset> presets() {
    return presets;
  }

  /**
   * O marcado {@code default}; se nenhum estiver marcado, o primeiro da lista; vazio se a lista
   * estiver vazia.
   */
  public Optional<ServerPreset> defaultPreset() {
    return presets.stream()
        .filter(ServerPreset::isDefault)
        .findFirst()
        .or(() -> presets.stream().findFirst());
  }

  public static ServerDirectory load() {
    Path external = Path.of(System.getProperty(EXTERNAL_FILE_PROPERTY, DEFAULT_EXTERNAL_FILE));
    if (Files.isRegularFile(external)) {
      try {
        ServerDirectory bundled = loadBundled();
        List<ServerPreset> parsed =
            parse(Files.readString(external, StandardCharsets.UTF_8), false).stream()
                .map(p -> withOfficialIdentity(p, bundled))
                .toList();
        LOG.info(
            "servers.json externo carregado de {} ({} servidor(es))",
            external.toAbsolutePath(),
            parsed.size());
        return new ServerDirectory(parsed);
      } catch (IOException | JsonParseException e) {
        LOG.warn(
            "falha ao ler {} ({}) — usando a lista embutida",
            external.toAbsolutePath(),
            e.getMessage());
      }
    }
    return loadBundled();
  }

  static ServerDirectory loadBundled() {
    try (InputStream in = ServerDirectory.class.getResourceAsStream(BUNDLED_RESOURCE)) {
      if (in == null) {
        LOG.warn("recurso embutido {} não encontrado", BUNDLED_RESOURCE);
        return new ServerDirectory(List.of());
      }
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return new ServerDirectory(parse(json, true));
    } catch (IOException | JsonParseException e) {
      LOG.warn("falha ao ler o servers.json embutido: {}", e.getMessage());
      return new ServerDirectory(List.of());
    }
  }

  /**
   * {@code true} só se o catálogo embutido tiver um preset oficial com o mesmo host, porta e TLS —
   * usado para não confiar num {@code official} vindo de fora (escolha salva no dispositivo).
   */
  public static boolean isOfficialEndpoint(String host, int port, boolean tls) {
    return tls
        && loadBundled().presets().stream()
            .anyMatch(p -> p.official() && p.host().equalsIgnoreCase(host) && p.port() == port);
  }

  /**
   * Atualiza um registro antigo (escolha salva, {@code servers.json} externo) que aponta para o
   * servidor oficial <b>sem</b> identidade MSS: devolve o preset oficial do catálogo embutido, com
   * a identidade dele (o oficial só aceita conta MSS). Qualquer outro preset volta inalterado.
   */
  public static ServerPreset withOfficialIdentity(ServerPreset preset) {
    return withOfficialIdentity(preset, loadBundled());
  }

  static ServerPreset withOfficialIdentity(ServerPreset preset, ServerDirectory bundled) {
    if (preset == null || preset.identity() != null || !preset.tls()) {
      return preset;
    }
    return bundled.presets().stream()
        .filter(p -> p.official() && p.identity() != null && p.sameEndpoint(preset))
        .findFirst()
        .map(
            p ->
                new ServerPreset(
                    p.name(), p.host(), p.port(), preset.isDefault(), true, true, p.identity()))
        .orElse(preset);
  }

  private static List<ServerPreset> parse(String json, boolean allowOfficial) {
    Type type = new TypeToken<List<ServerPresetJson>>() {}.getType();
    List<ServerPresetJson> raw = new Gson().fromJson(json, type);
    if (raw == null) {
      return List.of();
    }
    return raw.stream().map(r -> r.toPreset(allowOfficial)).toList();
  }

  /**
   * DTO só para o Gson — {@code default} é palavra reservada em Java, não dá para usar como nome de
   * componente de record. {@code identityTls} é {@link Boolean} para distinguir ausente (TLS) de
   * {@code false}.
   */
  private record ServerPresetJson(
      String name,
      String host,
      int port,
      @SerializedName("default") boolean isDefault,
      boolean tls,
      boolean official,
      String identity,
      Boolean identityTls) {
    ServerPreset toPreset(boolean allowOfficial) {
      boolean keepOfficial = official && allowOfficial && tls;
      if (official && !keepOfficial) {
        LOG.warn(
            "preset \"{}\" marcado como oficial ignorado ({})",
            name,
            allowOfficial ? "sem tls" : "arquivo externo");
      }
      IdentityTarget target = null;
      if (identity != null && !identity.isBlank()) {
        try {
          target = IdentityTarget.parse(identity, identityTls == null || identityTls);
        } catch (IllegalArgumentException e) {
          throw new JsonParseException(e.getMessage(), e);
        }
        if (!target.plaintextAllowed()) {
          throw new JsonParseException(
              "identidade sem TLS só é permitida em localhost: " + target.authority());
        }
      }
      return new ServerPreset(name, host, port, isDefault, tls, keepOfficial, target);
    }
  }
}
