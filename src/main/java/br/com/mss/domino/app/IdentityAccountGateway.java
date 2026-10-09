package br.com.mss.domino.app;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Porta interna do desktop para a conta MSS (M1 deste repositório). A UI e os testes dependem só
 * desta interface; o adaptador {@link IdentityClientGateway} fala com o serviço de identidade pelo
 * {@code identity-client-java}. Uma instância por destino de identidade e perfil local de dados.
 * Porta do contrato do TchowStrick-Java-Desktop-Legado (M1, BUG-003), com a audiência do Dominó.
 *
 * <p>Todas as operações podem lançar {@link IdentityAccountException}, com mensagem em português
 * para o jogador. Nenhum método devolve ou registra tokens além de {@link #gameAccessToken()}.
 */
public interface IdentityAccountGateway extends AutoCloseable {

  /** Audiência do acesso de jogo do Dominó no serviço de identidade. */
  String GAME_AUDIENCE = "domino";

  /** Carência da identidade MSS para confirmar o contato (MSSIdentity D4). */
  Duration GRACE_PERIOD = Duration.ofHours(1);

  /** Estado da conta, espelhando {@code mss.identity.v1.AccountState}. */
  enum AccountState {
    /** Contato ainda não confirmado, dentro da carência: joga normalmente. */
    PROVISIONAL,
    ACTIVE,
    /** Carência vencida sem confirmar o contato: o servidor de jogo recusa. */
    RESTRICTED
  }

  /** Finalidade do código enviado ao contato. */
  enum Purpose {
    VERIFY_CONTACT,
    RECOVER_ACCOUNT
  }

  /** Canais que o serviço de identidade aceita para cadastro/recuperação. */
  record Capabilities(boolean email, boolean phone) {}

  /** Resumo da sessão local — sem o token. */
  record AccountStatus(String accountId, AccountState state, Instant sessionExpiresAt) {}

  /** Código pedido ao serviço; {@code challengeId} identifica o desafio, não é segredo. */
  record Challenge(String challengeId, Purpose purpose, Instant expiresAt) {}

  /**
   * Resultado do cadastro. {@code account} vazio = o e-mail já tem conta: nenhuma sessão foi
   * concedida e o caminho é a recuperação ({@code challenge}, se presente, já é de {@link
   * Purpose#RECOVER_ACCOUNT}).
   */
  record SignUp(Optional<AccountStatus> account, Optional<Challenge> challenge) {}

  record Profile(
      String accountId,
      String nick,
      String avatarId,
      String maskedContact,
      boolean contactVerified) {}

  Capabilities capabilities();

  /** Cria conta com nick + e-mail. Com sessão concedida, ela já fica guardada localmente. */
  SignUp signUp(String nick, String email);

  /** Pede (ou reenvia) um código ao e-mail. */
  Challenge requestCode(String email, Purpose purpose);

  /** Confirma o e-mail com o código; renova e guarda a sessão. */
  AccountStatus confirmContact(String email, Challenge challenge, String code);

  /** Recupera a conta com o código; emite e guarda nova sessão neste dispositivo. */
  AccountStatus recover(String email, Challenge challenge, String code);

  /** Sessão guardada localmente, sem rede. Vazio = não entrou neste destino. */
  Optional<AccountStatus> currentAccount();

  /**
   * Estado atual da conta, consultado na identidade: renova a sessão só se estiver perto de
   * expirar, confere no perfil se o contato já foi confirmado e, sem confirmação, deriva
   * PROVISIONAL/RESTRICTED pela carência ({@link #GRACE_PERIOD}); o estado derivado fica guardado.
   */
  AccountStatus refreshStatus();

  /**
   * O servidor de jogo recusou a conta por contato não confirmado: guarda o estado RESTRICTED na
   * sessão local (sem rede). Sem sessão, não faz nada.
   */
  void markRestricted();

  /**
   * Estado a exibir quando a identidade não informa: contato confirmado → ACTIVE; RESTRICTED
   * guardado continua RESTRICTED; PROVISIONAL vira RESTRICTED depois da carência contada de {@code
   * provisionalSince} (primeira vez em que este dispositivo viu a conta provisória — a conta é no
   * máximo tão nova quanto isso, então a restrição derivada nunca é precoce). Vazio: sem referência
   * de tempo, só a identidade sabe.
   */
  static Optional<AccountState> deriveState(
      AccountState stored,
      boolean contactVerified,
      Optional<Instant> provisionalSince,
      Instant now) {
    if (contactVerified || stored == AccountState.ACTIVE) {
      return Optional.of(AccountState.ACTIVE);
    }
    if (stored == AccountState.RESTRICTED) {
      return Optional.of(AccountState.RESTRICTED);
    }
    return provisionalSince.map(
        since ->
            now.isBefore(since.plus(GRACE_PERIOD))
                ? AccountState.PROVISIONAL
                : AccountState.RESTRICTED);
  }

  Profile profile();

  /** {@code null} mantém o valor atual. */
  Profile updateProfile(String nick, String avatarId);

  /**
   * Acesso de jogo da audiência {@value #GAME_AUDIENCE}, renovado antes de expirar (a sessão é
   * renovada antes, se preciso). Nunca registre o valor.
   */
  String gameAccessToken();

  /**
   * O servidor de jogo recusou o último acesso: descarta o acesso em cache para que o próximo
   * {@link #gameAccessToken()} emita outro.
   */
  void invalidateGameAccess();

  /** Revoga a sessão deste dispositivo ou de todos e limpa a sessão local (mesmo se falhar). */
  void signOut(boolean allDevices);

  @Override
  void close();
}
