---
id: M1
tipo: marco
titulo: Conta MSS e servidor oficial no desktop
status: em-validacao
prioridade: P1
esforco: G
depende_de: [M0, Domino:M7, Domino:M6-06]
relacionados: [Domino:ADR-0032, Domino:ADR-0030, Domino:ADR-0031, TchowStrick-Java-Desktop-Legado:M1, TchowStrick-Java-Desktop-Legado:BUG-002, TchowStrick-Java-Desktop-Legado:BUG-003, TchowStrick-Java-Desktop-Legado:BUG-004, TchowStrick-Java-Desktop-Legado:BUG-005, TchowStrick:BUG-020]
evidencia: verificado
branch: feature/m1-conta-mss
integracao: branch
validacao: pendente
atualizado_em: 2026-10-08
---

# M1 — Conta MSS e servidor oficial no desktop

## Resultado para o usuário

O jogador do cliente Swing entra (ou cria) a conta MSS e joga num servidor do Dominó que exige conta — o oficial `domino.minashonsoftware.com.br:443` ou um servidor local com identidade —, com várias janelas no mesmo usuário do Windows, cada uma com a sua conta. LAN, `--embedded-server` e servidores sem conta continuam exatamente como antes.

Lado cliente do [Domino:M7](../../../Domino/docs/marcos/M07-conta-mss.md) (servidor em `DOMINO_IDENTITY_MODE=local|remote`, [Domino:ADR-0032](../../../Domino/docs/adr/0032-conta-mss-modos-local-remote.md)) e do preset oficial do Domino:M6-06. Modelo: [TchowStrick-Java-Desktop-Legado:M1](../../../TchowStrick-Java-Desktop-Legado/docs/marcos/M01-identidade-mss.md), na versão mais madura (branch `fix/mensagens-conta-mss`, com BUG-002/003 corrigidos).

## Escopo

- Incluído: itens abaixo; dependência `br.com.mss.identity:identity-client-java`; documentação de operação e compatibilidade.
- Fora de escopo: servidor, contrato protobuf e identidade (outros repositórios); exclusão de conta, telefone/SMS e convidado no desktop; publicação e qualquer acesso à produção; tornar o "Oficial" o servidor padrão (decisão do responsável na publicação, ver Decisões).

## Itens

| ID | Item | Status | Integração | Validação |
|---|---|---|---|---|
| M1-01 | `identity` por servidor (`servers.json`, escolha salva, `--identity=`/`--identity-plaintext`), texto puro só em loopback | em-validacao | branch | pendente |
| M1-02 | Preset oficial embutido (Domino:M6-06) e atualização da escolha salva antiga do oficial | em-validacao | branch | pendente |
| M1-03 | Conta MSS: entrar/criar (nick + e-mail → código), confirmar, recuperar, estado, nick/avatar, sair deste dispositivo/de todos, trocar de conta | em-validacao | branch | pendente |
| M1-04 | Acesso de jogo da audiência `domino` em `authorization: Bearer` de toda chamada; nova tentativa única; keepalive e reabertura do stream | em-validacao | branch | pendente |
| M1-05 | Mensagens de recusa por origem e caso (contrato de erros do Domino:M7) | em-validacao | branch | pendente |
| M1-06 | Perfil local de dados por janela (`--perfil=`, trava por arquivo) e "Gerenciar contas…" | em-validacao | branch | pendente |

## Como ficou

**Configuração (`net.config`).** `ServerPreset` ganhou `IdentityTarget identity` (opcional). `servers.json` aceita `"identity": "host:porta"` e `"identityTls"` (padrão `true`); destino em texto puro fora de localhost invalida o arquivo (cai para o embutido, com aviso). O embutido traz "Local" (`localhost:1099`, **padrão**) e "Oficial" (`domino.minashonsoftware.com.br:443`, TLS, `official`, identidade `identity.minashonsoftware.com.br:443` com TLS). `ServerDirectory.withOfficialIdentity` troca um registro do oficial sem identidade (escolha salva, `servers.json` externo) pelo preset novo; o `ConnectionResolver` regrava a escolha atualizada. `LocalServerChoiceStore` lembra o destino de identidade. Na linha de comando, `--identity=host:porta` só vale com `--server=`; `--identity-plaintext` só para loopback; uso inválido encerra com mensagem.

**Conta (`app`).** Portados do legado do TchowStrick, já com as correções de lá: porta `IdentityAccountGateway` (audiência `domino`) e adaptador `IdentityClientGateway` sobre o `identity-client-java` (estado consultado com carência de 1 h — BUG-003 de lá; `deviceId` sempre do perfil local, sem construtor que grave fora dele — BUG-004 de lá); `MssAccountFlow` (fluxos sem Swing, aviso de cadastro que não afirma envio); `LocalIdentitySessionStore` (um nó por destino de identidade, dentro do perfil local; token nunca em log); `IdentityGameCredentials`.

**Perfis locais por janela (`DataProfile`).** Cada janela trava `~/.domino/perfis/<nome>.lock` (ou `-Ddomino.data.dir`). Sem opção, a 1ª janela usa `padrao` (os mesmos nós de Preferences de antes: dados existentes preservados) e as seguintes `perfil-2`, `perfil-3`…; `--perfil=<nome>` escolhe e recusa se já estiver aberto. Sessão MSS, tokens de assento e perfis de jogador ficam no perfil; a escolha de servidor é comum. Tokens de assento num servidor com identidade ficam também separados por conta MSS. "Gerenciar contas…" (barra de perfil) lista contas MSS e perfis locais deste computador (sem tokens), remove dados locais (com "Sair também no servidor" opcional), entra na conta e abre nova janela em outro perfil local. Versão reduzida da do TchowStrick: o Dominó não tem conta oficial antiga nem carteira; perfis de jogador seguem em "Trocar perfil…".

**Transporte (`net.grpc`).** `GameCallCredentials` anexa `authorization: Bearer <acesso de jogo>` a **toda** chamada quando o servidor tem identidade; sem identidade, nenhum cabeçalho. `CredentialRetry`: `UNAUTHENTICATED` do servidor gera **uma** nova tentativa com o acesso descartado e reemitido (unárias e abertura de `Join`/`Reconnect`); nunca laço. Durante a partida, `GetMyStats` a cada 1 min entrega ao servidor o acesso renovado (o servidor adota a renovação mais recente da conta nos streams — Domino:M7); se mesmo assim o stream de eventos cair com `UNAUTHENTICATED`, é reaberto uma vez com o token de assento e acesso renovado (lições TchowStrick-Java-Desktop-Legado:M1 e TchowStrick:BUG-020). O keepalive só roda com conta MSS e não descarta o cache (correção do BUG-005 do legado do TchowStrick: decide por `source()`). Conta nunca vai em texto puro fora de localhost. Em servidor com identidade o `guest_id` enviado (Join, estatísticas, keepalive) é o `account_id`.

**Mensagens (`GrpcErrors` → `AccountRefusedException`).** Decididas pela origem e pelo caso (nunca "sessão expirada" genérica):

| Origem / resposta | Caso | Mensagem e ação |
|---|---|---|
| Servidor `UNAUTHENTICATED` "conta MSS necessária neste servidor" | `SIGN_IN_REQUIRED` | "Este servidor só aceita jogadores com conta MSS. Entre ou crie a conta em Conta MSS… (barra "Conta MSS" no topo da janela)." + oferta de entrar; se o servidor estiver configurado aqui sem conta, sugere "Trocar servidor…" |
| Servidor `UNAUTHENTICATED` "acesso de jogo inválido ou expirado…" | `ACCESS_REJECTED` (só depois da nova tentativa) | "O servidor de jogo recusou o acesso da sua conta MSS, mesmo depois de renová-lo…" com "(servidor: …)" + oferta de entrar de novo (sem laço) |
| Servidor `PERMISSION_DENIED` "conta restrita: confirme o contato…" | `ACCOUNT_RESTRICTED` | "Conta MSS restrita…" + "Confirmar o e-mail agora?"; o estado local passa a restrita |
| Servidor `UNAVAILABLE` "identidade MSS indisponível" | `IDENTITY_UNAVAILABLE_ON_SERVER` | "O serviço de identidade MSS está indisponível para o servidor de jogo agora. Tente novamente mais tarde." (sem falar em sessão/e-mail) |
| Servidor `PERMISSION_DENIED` "assento pertence a outra conta" | `SEAT_OF_OTHER_ACCOUNT` | "Este assento pertence a outra conta MSS…" |
| Local: identidade não renovou a sessão | `LOCAL_SESSION_ENDED` | mensagem da identidade + oferta de entrar de novo |
| Local: identidade fora do ar (deste computador) | `LOCAL_IDENTITY_UNAVAILABLE` | "Serviço de identidade MSS indisponível: este computador não conseguiu falar com ele…" |
| Qualquer outro erro | — | mensagem de sempre (servidores sem conta não mudam) |

**Interface.** Barras do topo: "Perfil: … · perfil local: …" com "Trocar perfil…" e "Gerenciar contas…"; "Servidor: Oficial [TLS] (conta MSS)"; barra "Conta MSS: <nick> (<estado>)" com "Conta MSS…" e "Trocar de conta…" (só com servidor com identidade); título com `[perfil local: perfil-2]` fora do padrão. "Conectar" num servidor com identidade abre entrar/criar se não houver conta; conta restrita oferece confirmar antes. Mudança de conta fora de partida fecha a conexão e volta para "Conectar" (o `guest_id` e os tokens eram da conta anterior). O Dominó não tem barra de menus; os acessos ficam nessas barras.

## Decisões (do agente, rotineiras; registradas aqui)

1. **"Local" continua padrão; "Oficial" entra na lista sem ser padrão.** O servidor oficial ainda não está publicado (Domino:M6-06) e as regras locais proíbem apontar o cliente para produção sem autorização operacional. Tornar o Oficial padrão (como no TchowStrick) fica para o responsável no momento da publicação — mudança de uma linha no `servers-default.json`.
2. Sem "conta oficial antiga" (`tchowstrick.auth.v1`): o Dominó nunca teve.
3. Nome na mesa num servidor com identidade: nick lembrado da conta MSS; sem ele, o do perfil de jogador.
4. Repositório `github-mss-identity` (GitHub Packages) declarado no `pom.xml`, conforme o README do `identity-client-java`; credencial só no `~/.m2/settings.xml`. Sem ela, o build usa o Maven local.

## Riscos

| Risco | Mitigação | Rollback |
|---|---|---|
| Servidor muda texto de erro do contrato | Casamento por trechos ("conta MSS necessária", "restrita", "outra conta", "identidade"); `GrpcErrorsTest` com os textos do Domino:M7 | Ajustar `GrpcErrors` |
| `identity-client-java` SNAPSHOT diverge | Versão em `identity.client.version`; registro em [compatibilidade](../compatibilidade.md) | Reinstalar a revisão anterior |
| Perfil local novo esconde dados de quem abria 2 janelas | Padrão usa os nós antigos | Usar `--perfil=padrao` |

## Verificações (08/10/2026, Windows 11, JDK 21.0.2)

| Verificação | Diretório / revisão | Resultado |
|---|---|---|
| `./mvnw -B -ntp verify` | worktree `.claude/worktrees/DominoLegado-m1`, `feature/m1-conta-mss` @ `0c68bdb` (código), contra `domino-server 1.0-SNAPSHOT` já instalado no Maven local e `identity-client-java 1.0-SNAPSHOT` do Maven local | `BUILD SUCCESS`; 236 testes, 0 falhas, 1 ignorado (`GrpcStagingTlsTest`); os 125 anteriores continuam passando (1 teste ajustado: o embutido agora tem um preset oficial); `spotless:check` OK |
| Textos de erro do servidor | `Domino` worktree `feature/m7-conta-mss` @ `201cf6b`, `AccountGuard.java` (constatado no código) | Mesmos textos casados por `GrpcErrors` |

Testes novos: `GrpcClientTransportAccountTest` (Bearer em toda chamada, sem cabeçalho sem conta, nova tentativa única, sem laço, recusas por caso, falha local não sai do cliente, keepalive, reabertura do stream, sem conta em texto puro remoto), `GrpcErrorsTest`, `IdentityPresetTest`, `IdentityLaunchTest`, `DataProfileTest`, `LocalAccountsServiceTest`, `LocalStoresIdentityTest`, e portados do legado do TchowStrick: `MssAccountFlowTest`, `IdentityClientGatewayTest` (identidade falsa em processo), `IdentityGameCredentialsTest`, `AccountStateDerivationTest`, `LocalIdentitySessionStoreTest`.

**Não executado:** interface gráfica; fluxo contra a identidade local real (o `MSSIdentity/compose.yaml` não estava no ar nesta tarefa); jogo ponta a ponta contra o servidor em `remote`; servidor oficial (nunca nesta tarefa).

## Limitações

- A reabertura automática do stream (e as reconexões automáticas já existentes) não reaplica o snapshot: eventos ocorridos entre a queda e a reabertura podem faltar até o próximo evento/reentrada. Comportamento herdado do retry da ADR-0019.
- Exclusão de conta, telefone/SMS e convidado não estão no desktop. Chamadas de rede seguem na EDT, como o resto do cliente.

## Validação manual

Ambiente local, nunca o oficial. Pré-requisitos: Docker; JDK 21.

1. Identidade: `cd MSSIdentity && ./mvnw -B package -DskipTests && docker compose up -d --build` (gRPC `localhost:9100`, Mailpit `http://localhost:8025`).
2. Servidor: no Domino (`feature/m7-conta-mss`), `./mvnw -B -ntp -DskipTests install` (instala também os artefatos que este cliente consome) e `docker compose -f deploy/compose.identidade-local.yml up -d` (servidor em `localhost:1099`, `DOMINO_IDENTITY_MODE=remote`; ordem e logs no cabeçalho do arquivo). Para o cenário 14 com servidor sem conta, `DOMINO_IDENTITY_MODE=local` antes do `up` (ou o `--embedded-server`).
3. Cliente: neste repositório, `./mvnw -B -ntp package -DskipTests` e `java -Ddomino.servers.file=config/servers-local-identidade.json -jar target/domino-client.jar` (no Windows, `run.cmd` com a mesma propriedade em `JAVA_TOOL_OPTIONS`, ou `java -D… -jar …` após `chcp 65001`). Para não tocar no perfil padrão, acrescente `--perfil=teste-m1`.

| # | Cenário | Resultado esperado |
|---|---|---|
| 1 | Abrir com a lista de desenvolvimento | "Servidor: Local (conta MSS) (conta MSS)"; barra "Conta MSS: não conectada" |
| 2 | Conectar, criar conta (nick + e-mail), código do Mailpit | Aviso sem afirmar envio; "E-mail confirmado. Conta ativa…"; lista de partidas; barra "Conta MSS: <nick> (ativa)" |
| 3 | Criar com e-mail já cadastrado | Nenhuma sessão; oferta de código para entrar; entra com ele |
| 4 | Criar partida, outra janela (`--perfil=b`, outra conta) entra, jogar até o fim, revanche | Servidor aceita; cada janela com sua conta; nome na mesa = nick MSS |
| 5 | Ficar 12+ min numa partida parada | Partida segue (keepalive a cada 1 min) |
| 6 | Conectar desistindo do cadastro | "O servidor … só aceita jogadores com conta MSS. Entre ou crie a conta em Conta MSS…"; não conecta |
| 7 | "Local (sem conta)" (Trocar servidor…) e Conectar contra o servidor em `remote` | "Este servidor só aceita jogadores com conta MSS…" + sugestão de "Trocar servidor…"; nenhuma menção a sessão expirada |
| 8 | Conta provisória após a carência (ou carência reduzida na identidade) e Criar partida | "Conta MSS restrita…" + "Confirmar o e-mail agora?"; confirmar libera |
| 9 | Parar a identidade (`docker compose stop identity`) com a sessão válida e Criar partida | Mensagem de identidade indisponível (do servidor ou deste computador), sem "sessão expirada" nem "confirme" |
| 10 | "Sair de todos" numa janela e agir na outra | Renovação falha → "Sua sessão da conta MSS expirou ou foi encerrada…" + oferta de entrar de novo; sem laço |
| 11 | Conta MSS… → editar nick/avatar; Sair deste dispositivo | Perfil salvo; barra volta a "não conectada"; Conectar pede conta |
| 12 | Duas janelas sem `--perfil` | 2ª com `[perfil local: perfil-2]`, sem conta; `--perfil=padrao` com a 1ª aberta → mensagem "já está aberto em outra janela" e sai |
| 13 | Gerenciar contas… | Lista contas das janelas com "esta janela"/"outra janela aberta"; remover da outra janela é recusado; "Nova janela" abre outra |
| 14 | `java -jar target/domino-client.jar --embedded-server --perfil=lan` e outra janela `--server=localhost:1099 --perfil=lan2` | Comportamento de antes; nenhuma barra "Conta MSS"; partida pela LAN (com o servidor do passo 2 parado, porque usa a mesma porta) |
| 15 | Escolha salva antiga do oficial sem identidade | Ao abrir, "Servidor: Oficial [TLS] (conta MSS)" (sem conectar ao oficial) |

Resultado: pendente (responsável).
