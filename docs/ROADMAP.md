---
id: ROADMAP
tipo: referencia
titulo: Roadmap — Domino Java Desktop (legado)
atualizado_em: 2026-10-09
---

# ROADMAP — Domino Java Desktop (legado)

Cliente Swing do Dominó em repositório próprio (Domino:ADR-0031). Este repositório preserva o cliente utilizável e o mantém compatível com o servidor autoritativo do Domino; o cliente web/mobile é o Domino:M8. O status de um marco com arquivo próprio fica no front matter desse arquivo; marcos ainda sem arquivo têm o status nesta tabela.

## Marcos

| Marco | Entregável ao usuário | Status | Especificação |
|---|---|---|---|
| M0 — Extração do Domino | Mesmo cliente desktop, agora buildado e executado a partir deste repositório | no marco | [M00](marcos/M00-extracao-do-domino.md) |
| M1 — Conta MSS e servidor oficial no desktop | Entrar com a conta MSS e jogar no servidor oficial do Dominó (Domino:M6/M7) | no marco | [M01](marcos/M01-conta-mss.md) |

## Horizonte

- **Agora:** M0 (PR #1) e M1 (PR #2) integrados e validados localmente pelo responsável em 09/10/2026; falta validar o M1 contra o servidor oficial depois da publicação do Domino:M6-06.
- **Em seguida:** tornar o "Oficial" o servidor padrão quando o responsável publicar o Domino:M6-06.
- **Mais tarde:** decidir aposentadoria ou manutenção mínima do cliente Swing quando o Domino:M8 existir.

## Pronto para começar (DoR)

Objetivo claro, critérios observáveis e versão do Domino consumida registrada em [compatibilidade](compatibilidade.md).

## Não-metas

- Hospedar ou alterar o servidor autoritativo, regras de jogo ou contrato protobuf — pertencem ao Domino.
- Novas features de produto sem aprovação do responsável.
