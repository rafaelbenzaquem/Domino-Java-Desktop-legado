# Domino Java Desktop (legado)

Cliente desktop Java 21/Swing do Dominó, com partida em rede via gRPC, servidor embarcado para LAN (`--embedded-server`), TLS opcional por servidor, conta MSS em servidores com identidade (inclusive o oficial), perfis locais, estatísticas e ranking. Extraído do módulo `domino-client-desktop` do [Domino](../Domino/README.md) em 08/10/2026 ([ADR-0031 de lá](../Domino/docs/adr/0031-cliente-desktop-em-repositorio-legado.md)); depende dos artefatos `domino-domain`, `domino-proto`, `domino-net-common` e `domino-server` daquele repositório e não hospeda outro servidor autoritativo.

Estado: extração ([M0](docs/marcos/M00-extracao-do-domino.md)) e conta MSS/servidor oficial ([M1](docs/marcos/M01-conta-mss.md)) implementados em branches, com build e testes automatizados verificados; validações manuais pendentes.

Comece pelo [índice](docs/README.md), [compatibilidade](docs/compatibilidade.md), [como rodar](docs/operacao/local.md) e [CHANGELOG](CHANGELOG.md). Leia [AGENTS.md](AGENTS.md) e o [padrão de documentação MSS](../docs/padroes/documentacao.md) antes de alterar o projeto.
