# Operação local e arquitetura — Domino Java Desktop (legado)

Conteúdo do cliente extraído de `Domino/docs/operacao/local.md` em 08/10/2026 ([ADR-0031 do Domino](../../../Domino/docs/adr/0031-cliente-desktop-em-repositorio-legado.md)). Servidor dedicado, containers, persistência e staging continuam documentados na [operação do Domino](../../../Domino/docs/operacao/local.md).

Comandos a executar na raiz deste repositório, salvo indicação. Builds podem baixar dependências e escrevem no repositório Maven local do usuário; operações que escrevem fora do portfólio exigem registrar impedimento conforme as [regras comuns](../../../AGENTS.md). O cliente grava perfil local, escolha de servidor, tokens de sessão e a sessão da conta MSS nas `Preferences` do usuário do SO, e um arquivo de trava por janela em `~/.domino/perfis/` (ou em `-Ddomino.data.dir=`), ao ser executado. Operação de produção é exclusiva do responsável autorizado.

## Arquitetura

```
domain  — regras puras e imutáveis (domino-domain, no Domino)
net     — GameTransport + DTOs + GameEvent (domino-net-common, no Domino);
          aqui: GrpcClientTransport, ClientChannelSecurity (TLS opcional),
          servers.json (net.config), busca em LAN (net.discovery.LanServerFinder)
          e PasswordHashing
app     — MatchController (eventos na EDT) + ClientEventBus + ClientGameView
          + armazenamentos locais (perfil, escolha de servidor, tokens)
          + conta MSS (M1): IdentityAccountGateway/IdentityClientGateway,
          MssAccountFlow, DataProfile (perfil local por janela)
ui      — Swing: MainWindow (conectar → lista → lobby → jogo), BoardView vetorial
```

Regra de dependência: `app` importa `net`+`domain`; `ui` importa `app` e nunca `net.grpc`. O `Main` depende de `domino-server` em *compile scope* porque embarca um `GrpcHostTransport` real para `--embedded-server` ([ADR-0020 do Domino](../../../Domino/docs/adr/0020-servidor-embarcado-no-cliente.md)) — a única aresta "cliente → servidor", deliberada ([ADR-0026 do Domino](../../../Domino/docs/adr/0026-multi-modulo.md)). Artefatos e APIs consumidos: [compatibilidade](../compatibilidade.md).

## Requisitos

- JDK 21+ (o código compila com `release 21`).
- Checkout do [Domino](../../../Domino/README.md) ao lado deste repositório, na versão indicada em [compatibilidade](../compatibilidade.md).
- Não precisa ter Maven instalado — use o wrapper `./mvnw` (Linux/macOS/Git Bash) ou `mvnw.cmd` (Windows). O primeiro build baixa o Maven e os plugins.
- Docker **não** é necessário aqui: os testes deste repositório sobem o servidor em memória, no próprio processo. Docker continua necessário para o `verify` completo do Domino (Testcontainers); por isso o passo 1 abaixo pula os testes.

## Build

1. No Domino, instalar `domino-domain`, `domino-proto`, `domino-net-common`, `domino-server` (e o pom agregador) no repositório Maven local:

   ```bash
   cd ../Domino
   ./mvnw -B -ntp -DskipTests install
   ```

2. Neste repositório:

   ```bash
   ./mvnw clean verify
   ```

   Roda os testes do cliente, o relatório JaCoCo (sem mínimo) e o `spotless:check`, e gera `target/domino-client.jar` (jar único executável, `Main-Class` = `br.com.mss.domino.Main`).

Como `domino.version` é `SNAPSHOT`, o build usa o que estiver instalado no Maven local: reinstale a partir da revisão do Domino desejada antes de buildar.

## Como rodar o cliente

**No Windows, prefira `run.cmd`** em vez de `java -jar`/`exec:java` direto — veja por quê em [Logs](#logs).

```bash
./mvnw -q exec:java                   # Linux/macOS/Git Bash — ou: java -jar target/domino-client.jar
run.cmd                               # Windows — ou: java -jar target\domino-client.jar
```

Para jogar em rede local com o servidor dedicado, suba-o primeiro no Domino ([operação do Domino](../../../Domino/docs/operacao/local.md), `docker compose up -d --build` ou `run-server.cmd`) e abra dois ou mais clientes.

Em cada cliente: a barra **"Servidor: `<nome>`"** no topo já mostra pra onde vai conectar (padrão: preset "Oficial" do `servers.json` embutido, `domino.minashonsoftware.com.br:443`, desde 09/10/2026; para jogar em LAN, escolha "Local" — `localhost:1099` — uma vez e a escolha fica salva) — **Trocar servidor…** abre a lista de presets + busca em LAN + endereço personalizado (ADR-0022 do Domino), sem digitar host/porta no caminho comum. **Conectar** → **Criar partida** (modo "um contra todos", 2–4 jogadores, Aberta ou Fechada com senha — ADR-0014) ou **Entrar** por uma da lista (que se atualiza sozinha a cada ~4s; partidas 🔒 pedem a senha antes) → no lobby, a partida começa quando enche ou o criador clica **Começar**. Compra do dorme e passe são automáticos; jogada fora da vez é recusada pelo servidor. Fechar a janela do cliente encerra o processo (não fica nada rodando em segundo plano).

A senha de uma partida nunca trafega em claro — só um hash SHA-256, tanto ao criar quanto ao entrar; o servidor nunca vê a senha em si, só compara hashes.

**Queda de rede ou fechar o cliente (ADR-0019 do Domino, revisado em 18/09/2026):** uma queda de Wi-Fi transitória reconecta sozinha, sem o jogador perceber nada — o `session_token` nunca aparece na tela. Fechar o cliente (queda, ou de propósito) e reabrir dentro da janela de 5 min (ADR-0013) não exige nenhuma ação separada de "retomar": a partida continua aparecendo na lista normal (`↩` na frente do nome sinaliza que este perfil já esteve nela, e ela já vem pré-selecionada quando é a única) — **selecionar e clicar "Entrar" já reconecta direto no mesmo assento**, sem pedir senha de novo. O campo "Token de sessão (opcional)" da tela de conectar continua existindo só como caminho avançado/manual (ex.: reconectar de outra máquina, sem o armazenamento local desta).

**Por perfil local, não por instalação** (`LocalSessionTokenStore`, ADR-0018 do Domino): o `session_token` fica no `Preferences` do Windows, que é por **conta do SO**, não por processo/janela. `LocalSessionTokenStore.useProfile(...)` escopa toda leitura/gravação ao perfil local ativo — `MainWindow` chama isso sempre que o perfil muda (abertura e "Trocar perfil…"). Pra testar dois jogadores na mesma máquina, cada cliente precisa estar num perfil local diferente ("Trocar perfil…"); a mesma conta do Windows pode ter vários. O token salvo é por servidor (Domino:BUG-009).

**Outro servidor sem abrir o diálogo:** `--server=host:porta` na linha de comando pré-seleciona esse servidor (prioridade máxima — acima da última escolha manual salva e do preset padrão do `servers.json`):

```bash
java -jar target/domino-client.jar --server=192.168.0.5:1100
run.cmd --server=192.168.0.5:1100
```

**Lista de servidores própria:** um `servers.json` no diretório de trabalho (ou no caminho da propriedade de sistema `-Ddomino.servers.file=...`) substitui a lista embutida inteira ([servers-default.json](../../src/main/resources/servers-default.json)) — formato:

```json
[
  {"name": "Casa", "host": "192.168.0.5", "port": 1100, "default": true}
]
```

**TLS por servidor (Domino:M6-02, ADR-0030 do Domino):** `"tls": true` num preset faz o cliente abrir o canal com TLS e validação de certificado obrigatória (cadeia padrão da JVM); sem o campo, ou com `false`, é texto puro, como antes. LAN, servidor embarcado, `--server=` e "Endereço personalizado…" são sempre texto puro. A escolha salva lembra o `tls`. Presets com TLS aparecem com `[TLS]` no diálogo e na barra "Servidor". O campo `"official"` só vale no `servers.json` embutido e junto com `tls`; num arquivo externo ele é ignorado, com aviso no log.

```json
[
  {"name": "Staging", "host": "localhost", "port": 8443, "default": true, "tls": true}
]
```

Só para staging com certificado de desenvolvimento (ex.: Caddy com `tls internal`, Domino:M6-03), `-Ddomino.tls.devCaFile=<caminho do PEM da CA>` faz o cliente confiar **somente** nessa CA (o log avisa). Nunca existe opção de aceitar qualquer certificado.

```bash
java -Ddomino.tls.devCaFile=C:/caminho/root.crt -jar target/domino-client.jar
```

O teste opcional `GrpcStagingTlsTest` só roda com a variável de ambiente `DOMINO_STAGING_CA` definida (staging do Domino no ar); sem ela, aparece como ignorado.

O `servers.json` embutido traz "Oficial" (padrão; `domino.minashonsoftware.com.br:443`, TLS, conta MSS — [M1](../marcos/M01-conta-mss.md); publicado em 09/10/2026, Domino:M6-06) e "Local". Testes automatizados e validações de desenvolvimento não usam o oficial: use `--server=`, `-Ddomino.servers.file=` ou "Local".

## Conta MSS

Servidor com `"identity"` no `servers.json` (ou `--identity=` junto de `--server=`) exige conta MSS ([M1](../marcos/M01-conta-mss.md)). LAN, `--embedded-server` e servidores sem `identity` seguem como antes.

```json
[
  {"name": "Local (conta MSS)", "host": "localhost", "port": 1099, "default": true,
   "identity": "localhost:9100", "identityTls": false}
]
```

`identityTls` é `true` por padrão; `false` (texto puro) só é aceito para `localhost`/loopback — fora disso o arquivo é recusado (cai para o embutido, com aviso). Lista pronta para desenvolvimento: [`config/servers-local-identidade.json`](../../config/servers-local-identidade.json) ("Local (conta MSS)" e "Local (sem conta)", ambos `localhost:1099`).

```bash
java -Ddomino.servers.file=config/servers-local-identidade.json -jar target/domino-client.jar
java -jar target/domino-client.jar --server=localhost:1099 --identity=localhost:9100 --identity-plaintext
```

Ambiente local completo (nunca produção): identidade pelo `MSSIdentity/compose.yaml` (gRPC em `localhost:9100`, códigos no Mailpit `http://localhost:8025`) e servidor do Dominó em `remote` pelo `deploy/compose.identidade-local.yml` do Domino (M7). Passo a passo e cenários na [validação do M1](../marcos/M01-conta-mss.md#validação-manual).

- **Barras:** "Servidor: … (conta MSS)" e "Conta MSS: <nick> (<estado>)" com **Conta MSS…** (entrar/criar, estado, nick/avatar, confirmar e-mail, recuperar, sair deste dispositivo/de todos) e **Trocar de conta…**. "Conectar" pede a conta antes de falar com o servidor.
- **Chamadas:** toda chamada leva `authorization: Bearer` com o acesso de jogo da audiência `domino`; recusa `UNAUTHENTICATED` gera uma única nova tentativa com acesso renovado; durante a partida, um `GetMyStats` por minuto entrega ao servidor o acesso renovado. O `guest_id` enviado é o `account_id`.
- **Mensagens:** decididas pela origem (identidade × servidor) e pelo caso; tabela no [M1](../marcos/M01-conta-mss.md#como-ficou).
- **Várias janelas:** cada janela trava um perfil local de dados (`padrao`, `perfil-2`…; `--perfil=<nome>` escolhe e recusa se estiver aberto) com sessão MSS, tokens de assento e perfis de jogador próprios. **Gerenciar contas…** (barra de perfil) lista e remove dados locais e abre nova janela. Tokens e sessões nunca aparecem na tela nem no log.

### Alternativa: servidor embarcado (ADR-0020 do Domino)

Sem precisar de um segundo processo/terminal, para uma partida casual em LAN (em memória, sem `DATABASE_URL`):

```bash
java -jar target/domino-client.jar --embedded-server                    # hospeda em localhost:1099
java -jar target/domino-client.jar --embedded-server --port=1100        # outra porta
java -jar target/domino-client.jar --embedded-server --no-lan-discovery # sem broadcast UDP
run.cmd --embedded-server                                               # idem, Windows
```

A janela já abre direto na lista de partidas (pulou "Conectar"). Fechar essa janela encerra tanto a sessão do cliente quanto o host embarcado — nenhum processo fica para trás. Um argumento mal-escrito (ex.: faltar o `=` em `--port=`) é reportado com um `WARNING` no log em vez de falhar em silêncio. O cliente não tem modo `--help`: argumentos desconhecidos geram aviso e a janela abre normalmente.

### Bancada de renderização (sem rede)

```bash
./mvnw -q test-compile exec:java@sandbox
```

## Logs

O cliente loga via **SLF4J + Logback** (config em [`src/main/resources/logback.xml`](../../src/main/resources/logback.xml), cópia da configuração do servidor no Domino; carregada automaticamente). O rastro de jogada sai em `DEBUG` (`RECV …` / `PLAY …`). Para o trace fino, rode com `-Dlogback.configurationFile=<arquivo>` apontando para um `.xml` com o logger `br.com.mss.domino` em `TRACE`.

**Console Windows mostrando acentos trocados (`n├úo`, `Mesa r?pida`)?** O log sai em UTF-8 de propósito; é o console que abre num codepage antigo (ex.: 850/437). O codepage do console só é aplicado de forma garantida se for trocado **antes** de o `java` subir, no mesmo processo de shell — é o que o `run.cmd` faz:

```bat
run.cmd                          :: cliente puro
run.cmd --embedded-server        :: servidor embarcado (ADR-0020 do Domino)
```

Funciona igual não importa de onde for chamado (PowerShell, `cmd`, Git Bash). Sem o script, rode `chcp 65001` você mesmo **na mesma sessão de shell**, antes de `java -jar ...` — ou use o Windows Terminal, que já é UTF-8 por padrão. O `run-server.cmd` do servidor dedicado continua no Domino.

## Qualidade

`./mvnw verify` roda, além dos testes:

- **Spotless** (`google-java-format`, mesma versão e configuração do Domino) — gate de formatação sobre todo o código-fonte; `./mvnw spotless:apply` formata.
- **JaCoCo** — relatório em `target/site/jacoco/`, sem mínimo (Swing não é testado por unidade neste projeto), como era no módulo do Domino.

Este repositório ainda não tem CI própria; o CI do Domino não builda mais o cliente ([ADR-0031 do Domino](../../../Domino/docs/adr/0031-cliente-desktop-em-repositorio-legado.md)).
