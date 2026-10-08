# Documentação — Domino Java Desktop (legado)

Organizada conforme o [padrão de documentação MSS](../../docs/padroes/documentacao.md). Fluxo de desenvolvimento: [regras comuns](../../AGENTS.md) (fluxo solo de 30/09/2026).

## Ordem de leitura

1. [Regras locais](../AGENTS.md).
2. [ROADMAP](ROADMAP.md) e marcos em [`marcos/`](marcos/) (status no front matter).
3. [Compatibilidade com o Domino](compatibilidade.md): artefatos e APIs consumidos, versão e revisão de origem.
4. [Operação local e arquitetura](operacao/local.md): build, execução, conexão, TLS, servidor embarcado e logs.
5. Validação: roteiros e regressões do sistema que usam este cliente (ex.: [roteiro do M6](../../Domino/docs/validacao/roteiros/M06.md)) continuam no Domino.
6. [Bugs](bugs/README.md).
7. [CHANGELOG](../CHANGELOG.md) e [histórico](historico/README.md).

Decisões de arquitetura (ADR-0001…ADR-0031), marcos M0–M8 e histórico de itens do cliente até 08/10/2026 (Fases 0–6a, M1–M6, `BUG-*`, `DEBT-*`) ficam no [índice do Domino](../../Domino/docs/README.md).

## Dicionário de IDs e caminhos legados

| Legado | Atual | Observação |
|---|---|---|
| `Domino/domino-client-desktop/` | raiz deste repositório | Movido em 08/10/2026 (Domino:ADR-0031); pacote `br.com.mss.domino`, `artifactId` `domino-client-desktop` e jar `domino-client.jar` mantidos |
| `./mvnw -pl domino-client-desktop …` (no Domino) | `./mvnw …` (aqui) | Após `./mvnw install` no Domino |
| `domino-client-desktop/target/domino-client.jar` | `target/domino-client.jar` | — |
| `Domino/run.cmd` | [run.cmd](../run.cmd) | Só o caminho do jar mudou; `run-server.cmd` fica no Domino |
| Seções do cliente em `Domino/docs/operacao/local.md` | [operacao/local.md](operacao/local.md) | Servidor continua lá |
| IDs `M0`–`M8`, `ADR-*`, `BUG-*`, `DEBT-*`, `RM-*`, Fases 0–6a | Mantidos | Pertencem ao Domino; citar como `Domino:ID` quando ambíguo |
| `M0` deste repositório | [Extração do Domino](marcos/M00-extracao-do-domino.md) | Numeração própria a partir de 08/10/2026 |
