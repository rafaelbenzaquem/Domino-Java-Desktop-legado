---
id: M0
tipo: marco
titulo: Extração do cliente desktop do Domino
status: em-validacao
prioridade: P2
esforco: P
depende_de: []
relacionados: [Domino:ADR-0031, Domino:ADR-0026, Domino:ADR-0020, TchowStrick:ADR-0020]
evidencia: verificado
branch: feature/m0-extracao-do-domino
integracao: branch
validacao: pendente
atualizado_em: 2026-10-08
---

# M0 — Extração do cliente desktop do Domino

## Resultado para o usuário

O jogador continua usando o mesmo cliente desktop Swing do Dominó, agora buildado e executado a partir deste repositório; o repositório do Domino passa a conter só domínio, contrato, rede comum e servidor.

## Escopo

- Incluído: código, testes e recursos de `Domino/domino-client-desktop/` (`main` @ `acaefff`), inclusive `src/test/resources/tls/` (Domino:M6-02), copiados sem alteração; `run.cmd`, wrapper Maven, `.gitattributes` e `.editorconfig` da raiz do Domino; `pom.xml` autônomo (sem parent), com as versões e configurações de plugins do agregador do Domino e a versão dos artefatos do Domino na propriedade `domino.version`; substituição do template inicial do IntelliJ (`pom.xml` com `groupId` incorreto e `src/main/java/br/com/mss/identity/Main.java` removidos); documentação mínima no padrão MSS e a operação do cliente movida do Domino; no Domino, remoção do módulo, ajuste de `pom.xml`, `Dockerfile`, `docker-compose.yml` e documentação (branch `feature/extracao-cliente-desktop` de lá).
- Fora de escopo: qualquer mudança de comportamento do cliente, do servidor ou do contrato; conta MSS e servidor oficial ([M1](../ROADMAP.md#m1--conta-mss-e-servidor-oficial-no-desktop)); CI própria; publicação de versão.

## Arquitetura e impacto

Decisão e alternativas na [ADR-0031 do Domino](../../../Domino/docs/adr/0031-cliente-desktop-em-repositorio-legado.md), no molde da [TchowStrick:ADR-0020](../../../TchowStrick/docs/adr/0020-cliente-desktop-em-repositorio-legado.md). Dependências, APIs consumidas e versão em [compatibilidade](../compatibilidade.md). Sem mudança de contrato, dados ou operação de produção.

Desvios deliberados do conteúdo copiado: apenas o `pom.xml` (refeito como autônomo; `groupId`, `artifactId`, `version`, `finalName` e `main.class` mantidos) e o caminho do jar no `run.cmd` (`%~dp0target\domino-client.jar`).

## Riscos

| Risco | Mitigação | Rollback |
|---|---|---|
| Artefatos do Domino ausentes ou desatualizados no repositório Maven local | Passo de `install` documentado; versão em `domino.version` | Reinstalar a partir da revisão desejada |
| Mudança no servidor quebra o cliente sem ser percebida (os testes ponta a ponta saíram do Domino) | Registrado na ADR-0031 e na compatibilidade | Rodar `./mvnw verify` aqui após reinstalar |
| Versões de dependências divergirem do reactor | `dependency:list` comparado com o do módulo original (idêntico) | Ajustar o `pom.xml` |
| Perda de histórico Git do módulo | Histórico permanece no Domino; origem registrada | Não aplicável |

## Itens

| ID | Item | Status | Integração | Validação |
|---|---|---|---|---|
| M0-01 | Copiar código, testes, recursos, wrapper e `run.cmd`; `pom.xml` autônomo | em-validacao | branch | pendente |
| M0-02 | Mover a operação do cliente e criar o conjunto mínimo de docs; ADR-0031 e ajustes no Domino | em-validacao | branch | pendente |

## Verificações (08/10/2026, Windows 11, Git Bash, JDK 21.0.2, Docker 28.5.1)

| Verificação | Diretório / revisão | Resultado |
|---|---|---|
| Linha de base: `./mvnw -B -ntp verify` | worktree descartável do Domino em `main` @ `acaefff` (sem alterações) | `BUILD SUCCESS`; domain 72, net-common 55, server 109 testes; `domino-client-desktop` 125 testes (19 classes), 0 falhas, 1 ignorado (`GrpcStagingTlsTest`, condicionado a `DOMINO_STAGING_CA`); gates de cobertura atendidos |
| Comparação byte a byte de 74 arquivos copiados contra `git show acaefff:<arquivo>` (68 do módulo, exceto o `pom.xml`, e 6 da raiz) | raiz do portfólio | 72 idênticos; diferem só o `run.cmd` (caminho do jar, deliberado) e o `mvnw.cmd` na cópia de trabalho (CRLF aplicado pelo `.gitattributes` na extração via `git archive`; o blob staged é o mesmo da origem, `92450f9`) |
| `./mvnw -B -ntp verify` | Domino, branch `feature/extracao-cliente-desktop` (base `acaefff`, módulo removido) | `BUILD SUCCESS`; reactor com 4 módulos; domain 72, net-common 55, server 109 testes, 0 falhas; gates de cobertura atendidos (inalterados) |
| `./mvnw -B -ntp -DskipTests install` | idem | `BUILD SUCCESS`; `domino` (pom), `domino-domain`, `domino-proto`, `domino-net-common` e `domino-server` 1.0-SNAPSHOT instalados no Maven local |
| `./mvnw -B -ntp verify` | este repositório, branch `feature/m0-extracao-do-domino` | `BUILD SUCCESS`; 125 testes, 0 falhas, 1 ignorado — mesma contagem por classe que a linha de base (19 relatórios surefire idênticos em testes/erros/falhas/ignorados); `spotless:check` OK; `target/domino-client.jar` gerado |
| `dependency:list` (escopo test) do módulo na linha de base × deste repositório | ambos | Listas idênticas (60 artefatos, mesmas versões e escopos) |
| `target/classes` da linha de base × deste repositório (`diff -r`) e lista de entradas do `domino-client.jar` (13 319) | ambos | Idênticos; manifesto com `Main-Class: br.com.mss.domino.Main` |
| `java -Djava.awt.headless=true -jar target/domino-client.jar --embedded-server --port=47391 --no-lan-discovery` (o cliente não tem modo `--help`) | este repositório | Host embarcado sobe (`HOST gRPC no ar na porta 47391`, `servidor embarcado no ar`), depois `HeadlessException` ao criar a janela, como esperado em modo headless; processo encerra com código 0. Mesmo resultado com o jar da linha de base |
| `docker build -t domino-server:extracao-teste .` (imagem removida em seguida) | Domino, branch `feature/extracao-cliente-desktop` | Sucesso, sem copiar o pom do cliente |

Não executado: interface gráfica real, partida em rede entre clientes e servidor dedicado com Postgres, staging TLS (`GrpcStagingTlsTest`) e servidor oficial.

## Validação manual

Pré-requisitos: JDK 21+; checkout do Domino na branch `feature/extracao-cliente-desktop` (ou `main` após o merge) ao lado deste repositório; Docker para o servidor dedicado.

Preparação (Git Bash):

```bash
cd Domino && ./mvnw -B -ntp -DskipTests install && cd ..
cd Domino-Java-Desktop-legado && ./mvnw clean verify
```

| # | Cenário | Resultado esperado |
|---|---|---|
| 1 | `./mvnw clean verify` neste repositório | `BUILD SUCCESS`, `target/domino-client.jar` gerado |
| 2 | `run.cmd` (ou `java -jar target/domino-client.jar`) | Janela do Dominó abre, como antes da extração, com o mesmo perfil local e a mesma escolha de servidor salvos |
| 3 | `run.cmd --embedded-server` numa janela e `run.cmd --server=localhost:1099` noutra, com perfis diferentes; uma cria, a outra entra | Partida em rede entre as duas instâncias pelo host embarcado |
| 4 | No Domino: `docker compose up -d --build`; aqui, duas instâncias com `--server=localhost:1099` | Partida pelo servidor dedicado, como antes |
| 5 | No Domino: `./mvnw -B -ntp verify` | Reactor com quatro módulos (`domino-domain`, `domino-proto`, `domino-net-common`, `domino-server`) passa; não há `domino-client-desktop` |
| 6 | Opcional, no Domino: `docker build -t domino-server .` | Imagem do servidor builda sem o cliente |

Resultado: pendente de execução pelo responsável. Os cenários 1, 5 e 6 já foram cobertos por execução do agente (acima); os cenários 2 a 4 exigem interface.
