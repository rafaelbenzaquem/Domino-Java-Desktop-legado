# Compatibilidade com o Domino

Registro da dependência deste cliente em relação ao [Domino](../../Domino/README.md). Atualizar ao trocar a versão consumida.

## Origem do código

| Campo | Valor |
|---|---|
| Repositório de origem | `Domino` (remoto `origin` do checkout do portfólio) |
| Caminho de origem | `domino-client-desktop/` (e `run.cmd`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/`, `.gitattributes`, `.editorconfig` da raiz) |
| Revisão de origem | `main` @ `acaefff` (08/10/2026) |
| Decisão | [Domino:ADR-0031](../../Domino/docs/adr/0031-cliente-desktop-em-repositorio-legado.md) |
| Conferência | Detalhes e resultado na [verificação do M0](marcos/M00-extracao-do-domino.md) (seção "Verificações") |

## Artefatos consumidos

| Artefato | Declarado no `pom.xml` | Uso no cliente |
|---|---|---|
| `br.com.mss.domino:domino-net-common` | sim | `GameTransport`/`GameEvent`/`GameEventListener`, `TransportException`, `SessionExpiredException`, `net.dto.*`, `DtoMapper`, `ProtoMapper`, `NetworkConfig`, `net.discovery.DiscoveredServer`/`DiscoveryProtocol` |
| `br.com.mss.domino:domino-server` | sim (escopo `compile`) | `net.grpc.GrpcHostTransport` (host embarcado do `--embedded-server`, [Domino:ADR-0020](../../Domino/docs/adr/0020-servidor-embarcado-no-cliente.md), aresta deliberada da [Domino:ADR-0026](../../Domino/docs/adr/0026-multi-modulo.md)); nos testes, também `net.MatchService` |
| `br.com.mss.domino:domino-proto` | transitivo | Stubs gRPC `DominoHostGrpc` e mensagens (`net.grpc.proto.*`) usados pelo `GrpcClientTransport` e pelos testes |
| `br.com.mss.domino:domino-domain` | transitivo | Regras puras (`domain.*`, `domain.setup.Dealer` nos testes) |
| `br.com.mss.domino:domino` (pom agregador) | transitivo | Parent dos artefatos acima: fornece as versões das dependências transitivas deles |

## Identidade MSS (M1)

| Artefato | Versão | Uso |
|---|---|---|
| `br.com.mss.identity:identity-client-java` (MSSIdentity M3-01) | `1.0-SNAPSHOT` (propriedade `identity.client.version`) | `IdentityClient` no adaptador `app.IdentityClientGateway`: conta, sessão, `issueGameAccess("domino")` e `invalidateGameAccess` |

Resolvido do Maven local (`./mvnw install` no MSSIdentity) ou do GitHub Packages (repositório `github-mss-identity` no `pom.xml`; token `read:packages` só no `~/.m2/settings.xml`). Verificado em 08/10/2026 com o jar instalado no Maven local em 06/10/2026; gRPC 1.68.1 e protobuf 3.25.5, os mesmos do Domino.

**Contrato de erros do servidor (Domino:M7, `AccountGuard`)** casado em `net.grpc.GrpcErrors` — constatado no código do Domino `feature/m7-conta-mss` @ `201cf6b`:

| Status | Descrição do servidor | Caso no cliente |
|---|---|---|
| `UNAUTHENTICATED` | "conta MSS necessária neste servidor" | `SIGN_IN_REQUIRED` |
| `UNAUTHENTICATED` | "acesso de jogo inválido ou expirado; entre novamente na conta MSS" | nova tentativa única; persistindo, `ACCESS_REJECTED` |
| `PERMISSION_DENIED` | "conta restrita: confirme o contato na conta MSS" | `ACCOUNT_RESTRICTED` |
| `UNAVAILABLE` | "identidade MSS indisponível" | `IDENTITY_UNAVAILABLE_ON_SERVER` |
| `PERMISSION_DENIED` | "assento pertence a outra conta" | `SEAT_OF_OTHER_ACCOUNT` |

O cliente envia `authorization: Bearer <acesso>` em toda chamada e o `account_id` como `guest_id`. Mudança desses textos ou do cabeçalho no Domino exige ajustar `GrpcErrors`/`GrpcErrorsTest` aqui.

Versão: propriedade `domino.version` do [pom.xml](../pom.xml), hoje `1.0-SNAPSHOT` (versão do agregador do Domino em `acaefff`). Como é `SNAPSHOT`, o build usa o que estiver instalado no repositório Maven local; instale a partir da revisão do Domino desejada antes de buildar ([operação local](operacao/local.md#build)).

O `domino-server` instalado é o jar sombreado do servidor (inclui dependências), exatamente como o reactor do Domino o entregava ao módulo do cliente; o cliente usa só as classes acima. As demais versões (gRPC 1.68.1, gson, SLF4J/Logback, JUnit, jqwik, plugins) foram copiadas do agregador do Domino em `acaefff`; ao mudá-las lá, conferir aqui.

O contrato de rede segue o [protobuf do Domino](../../Domino/domino-proto/src/main/proto/); mudanças incompatíveis lá exigem rebuild e teste deste cliente.

## Testes que cruzam os repositórios

`GrpcTransportTest`, `AppGrpcIntegrationTest`, `GrpcClientTransportSessionExpiredTest` e `GrpcHostTransportDiscoveryTest` sobem um `GrpcHostTransport` do `domino-server` em processo e exercitam cliente e servidor ponta a ponta. Ao alterar `net`, `net.grpc`, `net.discovery`, o host embarcado ou o `.proto` no Domino, reinstale os artefatos e rode `./mvnw verify` aqui.

## Servidor validado

| Revisão do Domino instalada | Data | Resultado |
|---|---|---|
| `acaefff` + remoção do módulo (branch `feature/extracao-cliente-desktop`) | 08/10/2026 | Ver [verificações do M0](marcos/M00-extracao-do-domino.md) (seção "Verificações") |
| `domino-server 1.0-SNAPSHOT` já instalado no Maven local (não reinstalado no M1; o M7 do servidor estava em desenvolvimento em paralelo) | 08/10/2026 | `verify` do M1 verde (ver [M1](marcos/M01-conta-mss.md), seção "Verificações"); integração com o servidor do M7 não executada |
