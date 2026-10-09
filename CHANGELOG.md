# Changelog — Domino Java Desktop (legado)

Mudanças relevantes do produto. Formato: [Keep a Changelog 1.1](https://keepachangelog.com/pt-BR/1.1.0/); versões: [SemVer 2.0](https://semver.org/lang/pt-BR/). Cada entrada cita o marco, item, bug ou ADR. Padrão: [documentação MSS](../docs/padroes/documentacao.md) §7.10.

Versões anteriores do cliente desktop foram publicadas junto do servidor, no [CHANGELOG do Domino](../Domino/CHANGELOG.md) (`v1.0.0` e entregas não publicadas até 08/10/2026); não são repetidas aqui.

## [Não publicado]

### Alterado
- "Oficial" (`domino.minashonsoftware.com.br:443`, TLS, conta MSS) passa a ser o servidor padrão do `servers.json` embutido e o primeiro da lista; "Local" (`localhost:1099`) continua em **Trocar servidor…**. A escolha salva no computador continua tendo prioridade (Domino:M6-06, publicado em 09/10/2026).

### Documentação
- README: como buildar e executar em outro computador (Domino e `identity-client-java` no Maven local ou token `read:packages`; erro 401 do `github-mss-identity`; `-U` após falha em cache).

### Adicionado
- Conta MSS no desktop: entrar/criar (nick + e-mail → código), confirmar e-mail, recuperar, estado, nick/avatar, sair deste dispositivo/de todos e trocar de conta, em servidores com `identity` (`servers.json`, escolha salva ou `--identity=`/`--identity-plaintext`); acesso de jogo da audiência `domino` em `authorization: Bearer` com nova tentativa única, keepalive na partida e reabertura do stream; mensagens de recusa por origem e caso, conforme o contrato do Domino:M7 (M1).
- Preset "Oficial" (`domino.minashonsoftware.com.br:443`, TLS, identidade MSS) no `servers.json` embutido, inicialmente sem ser o padrão; escolha salva antiga do oficial passa a usar o preset com identidade (M1, Domino:M6-06).
- Perfil local de dados por janela (`--perfil=`, trava em `~/.domino/perfis/`) e "Gerenciar contas…"; `config/servers-local-identidade.json` para desenvolvimento (M1).
- Dependência `br.com.mss.identity:identity-client-java` (M1).
- Repositório próprio do cliente desktop Swing, extraído do módulo `domino-client-desktop` do Domino `main` @ `acaefff` sem alteração de código, testes ou recursos; projeto Maven autônomo (`br.com.mss.domino:domino-client-desktop`, jar `domino-client.jar`) que consome `domino-net-common` e `domino-server` (e, por eles, `domino-domain` e `domino-proto`) instalados no repositório Maven local, versão na propriedade `domino.version` (M0; Domino:ADR-0031).
- `run.cmd` do cliente, movido do Domino; o caminho do jar passa a ser `target\domino-client.jar` (M0).
- Documentação do cliente: operação local e arquitetura (movida de `Domino/docs/operacao/local.md`), compatibilidade com o Domino, roadmap com o M1 "Conta MSS e servidor oficial no desktop" proposto (M0).
