---
id: ROADMAP
tipo: referencia
titulo: Roadmap — Domino Java Desktop (legado)
atualizado_em: 2026-10-08
---

# ROADMAP — Domino Java Desktop (legado)

Cliente Swing do Dominó em repositório próprio (Domino:ADR-0031). Este repositório preserva o cliente utilizável e o mantém compatível com o servidor autoritativo do Domino; o cliente web/mobile é o Domino:M8. O status de um marco com arquivo próprio fica no front matter desse arquivo; marcos ainda sem arquivo têm o status nesta tabela.

## Marcos

| Marco | Entregável ao usuário | Status | Especificação |
|---|---|---|---|
| M0 — Extração do Domino | Mesmo cliente desktop, agora buildado e executado a partir deste repositório | no marco | [M00](marcos/M00-extracao-do-domino.md) |
| M1 — Conta MSS e servidor oficial no desktop | Entrar com a conta MSS e jogar no servidor oficial do Dominó (Domino:M6/M7) | `proposto` | esta página |

## Horizonte

- **Agora:** M0 — validar build e uso do cliente extraído.
- **Em seguida:** M1 — conta MSS e servidor oficial no desktop, no molde do TchowStrick-Java-Desktop-Legado:M1; depende do Domino:M7 e da publicação do servidor oficial (Domino:M6-06).
- **Mais tarde:** decidir aposentadoria ou manutenção mínima do cliente Swing quando o Domino:M8 existir.

## Marcos sem arquivo próprio

### M1 — Conta MSS e servidor oficial no desktop

**Objetivo:** o jogador entra com a conta MSS e joga no servidor oficial do Dominó a partir deste cliente. Escopo, critérios e dependências a especificar ao começar; registrado em 08/10/2026 junto da extração, sem especificação aprovada.

**Depende de:** M0; Domino:M7 (conta MSS no servidor) e servidor oficial publicado (Domino:M6-06).

## Pronto para começar (DoR)

Objetivo claro, critérios observáveis e versão do Domino consumida registrada em [compatibilidade](compatibilidade.md).

## Não-metas

- Hospedar ou alterar o servidor autoritativo, regras de jogo ou contrato protobuf — pertencem ao Domino.
- Novas features de produto sem aprovação do responsável.
