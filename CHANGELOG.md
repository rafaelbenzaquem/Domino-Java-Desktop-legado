# Changelog — Domino Java Desktop (legado)

Mudanças relevantes do produto. Formato: [Keep a Changelog 1.1](https://keepachangelog.com/pt-BR/1.1.0/); versões: [SemVer 2.0](https://semver.org/lang/pt-BR/). Cada entrada cita o marco, item, bug ou ADR. Padrão: [documentação MSS](../docs/padroes/documentacao.md) §7.10.

Versões anteriores do cliente desktop foram publicadas junto do servidor, no [CHANGELOG do Domino](../Domino/CHANGELOG.md) (`v1.0.0` e entregas não publicadas até 08/10/2026); não são repetidas aqui.

## [Não publicado]

### Adicionado
- Repositório próprio do cliente desktop Swing, extraído do módulo `domino-client-desktop` do Domino `main` @ `acaefff` sem alteração de código, testes ou recursos; projeto Maven autônomo (`br.com.mss.domino:domino-client-desktop`, jar `domino-client.jar`) que consome `domino-net-common` e `domino-server` (e, por eles, `domino-domain` e `domino-proto`) instalados no repositório Maven local, versão na propriedade `domino.version` (M0; Domino:ADR-0031).
- `run.cmd` do cliente, movido do Domino; o caminho do jar passa a ser `target\domino-client.jar` (M0).
- Documentação do cliente: operação local e arquitetura (movida de `Domino/docs/operacao/local.md`), compatibilidade com o Domino, roadmap com o M1 "Conta MSS e servidor oficial no desktop" proposto (M0).
