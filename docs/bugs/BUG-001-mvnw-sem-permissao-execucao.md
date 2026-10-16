---
id: BUG-001
tipo: bug
titulo: "mvnw sem permissão de execução após a extração do Domino"
status: corrigido
severidade: S3
prioridade: P2
depende_de: []
relacionados: []
evidencia: "verificado por execução"
branch: fix/mvnw-permissao-execucao-e-build
versao: "1.0-SNAPSHOT"
atualizado_em: 2026-10-10
---

# BUG-001 — mvnw sem permissão de execução após a extração do Domino

## Classificação

| Campo | Valor | Guia |
|---|---|---|
| Severidade | S3 | Build local do zero quebra, mas tem contorno (`chmod +x`). |
| Prioridade | P2 | Afeta todo clone novo deste repositório; corrigido junto desta tarefa. |
| Origem | validação manual | Encontrado ao configurar o projeto num computador novo, seguindo o README. |

## Ambiente

Repositório `Domino-Java-Desktop-legado`, branch `main` @ `88d7df5`, macOS (Darwin 27.0.0), checkout limpo via `git clone`.

## Reprodução

1. `git clone https://github.com/rafaelbenzaquem/Domino-Java-Desktop-legado.git && cd Domino-Java-Desktop-legado`
2. `./mvnw -B -ntp clean verify`

**Esperado:** o wrapper roda normalmente, como no Domino (de onde este repositório foi extraído em 08/10/2026, [Domino:ADR-0031](../../../mss-domino-server/docs/adr/0031-cliente-desktop-em-repositorio-legado.md)).
**Observado:** `(eval):1: permission denied: ./mvnw`.
**Evidência:** `git ls-files --stage mvnw` mostra modo `100644` neste repositório contra `100755` no Domino, para o **mesmo blob** (`bd8896b…`) — ou seja, o conteúdo é idêntico; só o bit de execução não foi preservado na extração.

## Causa-raiz

Constatado no Git: ao extrair `domino-client-desktop/` (e `mvnw`, `mvnw.cmd`, `.mvn/` da raiz do Domino) para este repositório ([Domino:ADR-0031](../../../mss-domino-server/docs/adr/0031-cliente-desktop-em-repositorio-legado.md), 08/10/2026), o `mvnw` foi commitado sem o modo executável (`100644` em vez de `100755`). `mvnw.cmd` não precisa de bit de execução no Windows, mas foi conferido pelo mesmo motivo.

## Correção e regressão

Corrigido nesta branch com `git update-index --chmod=+x mvnw mvnw.cmd` (sem alterar o conteúdo dos arquivos — mesmo blob). Verificado por execução: `./mvnw -B -ntp clean verify` (JDK 21) completa com `BUILD SUCCESS` após a correção, com o Domino já instalado no Maven local. Regressão: o modo de arquivo fica registrado no Git a partir deste commit; um novo `chmod -x` acidental seria pego por `git diff`/`git status` antes do commit.
