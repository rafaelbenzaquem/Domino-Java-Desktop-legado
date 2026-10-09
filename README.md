# Domino Java Desktop (legado)

Cliente desktop Java 21/Swing do Dominó, com partida em rede via gRPC, servidor embarcado para LAN (`--embedded-server`), TLS opcional por servidor, conta MSS em servidores com identidade (inclusive o oficial), perfis locais, estatísticas e ranking. Extraído do módulo `domino-client-desktop` do [Domino](../Domino/README.md) em 08/10/2026 ([ADR-0031 de lá](../Domino/docs/adr/0031-cliente-desktop-em-repositorio-legado.md)); depende dos artefatos `domino-domain`, `domino-proto`, `domino-net-common` e `domino-server` daquele repositório e não hospeda outro servidor autoritativo.

Estado: extração ([M0](docs/marcos/M00-extracao-do-domino.md)) e conta MSS/servidor oficial ([M1](docs/marcos/M01-conta-mss.md)) integrados e validados pelo responsável (M1 também contra o servidor oficial, publicado em 09/10/2026). O preset "Oficial" (`domino.minashonsoftware.com.br:443`, TLS, conta MSS) é o servidor padrão; "Local" continua na lista de **Trocar servidor…**.

Comece pelo [índice](docs/README.md), [compatibilidade](docs/compatibilidade.md), [como rodar](docs/operacao/local.md) e [CHANGELOG](CHANGELOG.md). Leia [AGENTS.md](AGENTS.md) e o [padrão de documentação MSS](../docs/padroes/documentacao.md) antes de alterar o projeto.

## Executar em outro computador

O cliente não está em nenhum repositório Maven público. Ele depende de:

- `domino-net-common` e `domino-server` (e, por eles, `domino-domain` e `domino-proto`), que só existem no Maven local depois do `install` do [Domino](../Domino/README.md);
- `br.com.mss.identity:identity-client-java`, do [MSSIdentity](https://github.com/rafaelbenzaquem/mss-identity), que vem do GitHub Packages (com token) ou do Maven local.

**Sintoma típico:** `Could not transfer artifact br.com.mss.domino:domino-net-common:pom:1.0-SNAPSHOT from/to github-mss-identity (https://maven.pkg.github.com/rafaelbenzaquem/mss-identity): status code: 401`. Significa que o Domino não foi instalado neste computador: sem os artefatos no Maven local, o Maven os procura no único repositório remoto que aceita SNAPSHOT (o GitHub Packages do `mss-identity`), que exige credencial e nem os tem. O mesmo 401 para `identity-client-java` significa que falta o token (passo 2).

Requisitos: JDK 21+ e Git. Maven não é necessário (wrapper `./mvnw`; no Windows `mvnw.cmd`).

1. **Clonar lado a lado:**

   ```bash
   mkdir mss && cd mss
   git clone https://github.com/rafaelbenzaquem/domino.git Domino
   git clone https://github.com/rafaelbenzaquem/Domino-Java-Desktop-legado.git
   ```

2. **`identity-client-java`, uma das duas opções:**

   - **A, com token (recomendado):** crie um *personal access token (classic)* no GitHub só com `read:packages` e grave em `~/.m2/settings.xml` (Windows: `%USERPROFILE%\.m2\settings.xml`), **nunca** num repositório:

     ```xml
     <settings>
       <servers>
         <server>
           <id>github-mss-identity</id>
           <username>SEU_USUARIO_GITHUB</username>
           <password>SEU_TOKEN_READ_PACKAGES</password>
         </server>
       </servers>
     </settings>
     ```

     O `id` precisa ser exatamente `github-mss-identity`. O GitHub Packages exige autenticação mesmo para leitura.
   - **B, sem token:** instale a biblioteca a partir do código:

     ```bash
     git clone https://github.com/rafaelbenzaquem/mss-identity.git MSSIdentity
     cd MSSIdentity && ./mvnw -B -ntp -DskipTests -pl identity-contract,identity-client-java -am install && cd ..
     ```

3. **Instalar o Domino pela raiz** (não por um módulo isolado; sem testes, que exigem Docker):

   ```bash
   cd Domino && ./mvnw -B -ntp -DskipTests install && cd ..
   ```

4. **Buildar e abrir o cliente:**

   ```bash
   cd Domino-Java-Desktop-legado
   ./mvnw -B -ntp clean package -DskipTests      # ou clean verify, para rodar os testes
   java -jar target/domino-client.jar            # Windows: run.cmd
   ```

**Se já falhou antes:** o Maven guarda a falha (`This failure was cached in the local repository…`) e não tenta de novo. Depois de corrigir, repita com `-U` (ex.: `./mvnw -U -B -ntp clean package -DskipTests`).

**IntelliJ IDEA:** abra o Domino e este repositório como projetos separados. Rode o `install` pela raiz do Domino (janela Maven → `domino` → Lifecycle → `install`, com *Skip Tests*) antes de buildar o cliente. O IntelliJ lê o `~/.m2/settings.xml` do usuário (Settings → Build Tools → Maven → *User settings file*); para forçar a nova tentativa, marque *Always update snapshots* ou use o `-U`.

**Atualizar depois:** `git pull` nos repositórios e repita os passos 3 e 4. O cliente usa o que estiver instalado no Maven local (`domino.version` é SNAPSHOT); detalhes em [compatibilidade](docs/compatibilidade.md).
