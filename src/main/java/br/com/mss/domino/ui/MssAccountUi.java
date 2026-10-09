package br.com.mss.domino.ui;

import br.com.mss.domino.Main;
import br.com.mss.domino.app.DataProfile;
import br.com.mss.domino.app.DataProfileException;
import br.com.mss.domino.app.IdentityAccountException;
import br.com.mss.domino.app.IdentityAccountGateway;
import br.com.mss.domino.app.IdentityAccountGateway.AccountState;
import br.com.mss.domino.app.IdentityAccountGateway.AccountStatus;
import br.com.mss.domino.app.IdentityClientGateway;
import br.com.mss.domino.app.IdentityGameCredentials;
import br.com.mss.domino.app.IdentitySessionStore;
import br.com.mss.domino.app.LocalAccountsService;
import br.com.mss.domino.app.LocalIdentitySessionStore;
import br.com.mss.domino.app.MssAccountFlow;
import br.com.mss.domino.net.AccountCredentials;
import br.com.mss.domino.net.AccountRefusedException;
import br.com.mss.domino.net.config.IdentityTarget;
import br.com.mss.domino.net.config.ServerPreset;
import java.awt.BorderLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conta MSS na janela do Dominó (M1): guarda o {@link IdentityAccountGateway} do destino de
 * identidade do servidor ativo (um por janela, no {@link DataProfile} dela), o portão antes de
 * conectar, os fluxos de entrar/criar/confirmar/recuperar/sair e a explicação das recusas de conta.
 * Porta da fiação do {@code Main} do TchowStrick-Java-Desktop-Legado (M1, BUG-002/003), separada da
 * {@link MainWindow} para não misturar a conta com as telas do jogo.
 */
final class MssAccountUi {

  private static final Logger LOG = LoggerFactory.getLogger(MssAccountUi.class);

  private final JFrame owner;
  private final DataProfile dataProfile;
  private final Supplier<String> suggestedNick;
  private final List<String> launchArgs;

  /** Um gateway (e a sessão local por trás dele) por destino de identidade já usado. */
  private record Slot(IdentityAccountGateway gateway, IdentitySessionStore store) {}

  private final Map<IdentityTarget, Slot> slots = new HashMap<>();

  MssAccountUi(
      JFrame owner, DataProfile dataProfile, Supplier<String> suggestedNick, List<String> args) {
    this.owner = owner;
    this.dataProfile = dataProfile;
    this.suggestedNick = suggestedNick;
    this.launchArgs = List.copyOf(args);
  }

  // ------------------------------------------------------------------ gateway

  /**
   * Gateway do destino de identidade de {@code server} ({@code null} sem identidade). Fica em cache
   * por destino: trocar o servidor selecionado nunca fecha o gateway que a conexão aberta usa.
   */
  IdentityAccountGateway gateway(ServerPreset server) {
    Slot slot = slot(server);
    return slot == null ? null : slot.gateway();
  }

  private Slot slot(ServerPreset server) {
    IdentityTarget target = server == null ? null : server.identity();
    if (target == null) {
      return null;
    }
    Slot slot = slots.get(target);
    if (slot != null) {
      return slot;
    }
    try {
      IdentitySessionStore store = new LocalIdentitySessionStore(dataProfile, target);
      slot =
          new Slot(new IdentityClientGateway(target, store, dataProfile.identityDeviceId()), store);
    } catch (IllegalArgumentException e) {
      warn(e.getMessage());
      return null;
    }
    slots.put(target, slot);
    return slot;
  }

  private IdentitySessionStore storeOf(IdentityAccountGateway g) {
    return slots.values().stream()
        .filter(s -> s.gateway() == g)
        .map(Slot::store)
        .findFirst()
        .orElse(null);
  }

  /** Fecha todos os gateways (fim da janela, ou sessão local apagada em "Gerenciar contas"). */
  void close() {
    for (Slot slot : slots.values()) {
      try {
        slot.gateway().close();
      } catch (RuntimeException ignored) {
        // fechar o canal é best-effort
      }
    }
    slots.clear();
  }

  /** Credencial das chamadas de jogo para {@code server}: acesso da identidade ou nenhuma. */
  AccountCredentials credentials(ServerPreset server) {
    IdentityAccountGateway g = gateway(server);
    return g == null ? AccountCredentials.none() : new IdentityGameCredentials(g);
  }

  /** {@code account_id} da sessão MSS de {@code server}, se houver (vira o {@code guest_id}). */
  Optional<String> accountId(ServerPreset server) {
    IdentityAccountGateway g = gateway(server);
    return g == null ? Optional.empty() : g.currentAccount().map(AccountStatus::accountId);
  }

  /** Nick lembrado da conta MSS de {@code server}, sem rede. */
  Optional<String> nick(ServerPreset server) {
    IdentityAccountGateway g = gateway(server);
    IdentitySessionStore store = g == null ? null : storeOf(g);
    if (store == null) {
      return Optional.empty();
    }
    return g.currentAccount().flatMap(a -> store.nickFor(a.accountId()));
  }

  /** Texto da barra "Conta MSS": nick e último estado conhecido, sem rede. */
  String label(ServerPreset server) {
    IdentityAccountGateway g = gateway(server);
    if (g == null) {
      return "indisponível";
    }
    Optional<AccountStatus> account = g.currentAccount();
    if (account.isEmpty()) {
      return "não conectada";
    }
    String who =
        storeOf(g)
            .nickFor(account.get().accountId())
            .orElse(
                "conectada (" + LocalAccountsServiceIds.shortId(account.get().accountId()) + ")");
    return who + " (" + MssAccountFlow.stateLabel(account.get().state()) + ")";
  }

  // ------------------------------------------------------------------ portão

  /**
   * Portão antes de conectar a um servidor com identidade: exige sessão guardada; conta não ativa
   * tem o estado consultado na identidade e, se restrita, o jogador pode confirmar o contato antes
   * de seguir. O resto o servidor decide. {@code false} = não conectar.
   */
  boolean ensureAccount(ServerPreset server) {
    IdentityAccountGateway g = gateway(server);
    if (g == null) {
      return false;
    }
    if (g.currentAccount().isPresent()) {
      Optional<AccountState> state = currentState(g);
      if (state.isPresent()) {
        if (state.get() == AccountState.RESTRICTED) {
          return offerConfirmContact(server, MssAccountFlow.stateMessage(state.get()));
        }
        return true;
      }
      // a identidade encerrou a sessão: entrar de novo, abaixo
    }
    if (flow(g, server).signIn().isPresent()) {
      rememberProfile(g);
      return true;
    }
    warn(
        "O servidor "
            + server.name()
            + " só aceita jogadores com conta MSS. Entre ou crie a conta em "
            + AccountRefusedException.ACCOUNT_MENU
            + " para jogar.");
    return false;
  }

  /**
   * Estado para o portão: ACTIVE guardado vale sem rede; senão consulta a identidade. Falha que não
   * encerra a sessão (identidade fora do ar) fica com o último estado conhecido — o servidor de
   * jogo decide. Vazio se não houver mais sessão.
   */
  private static Optional<AccountState> currentState(IdentityAccountGateway g) {
    Optional<AccountStatus> stored = g.currentAccount();
    if (stored.isEmpty() || stored.get().state() == AccountState.ACTIVE) {
      return stored.map(AccountStatus::state);
    }
    try {
      return Optional.of(g.refreshStatus().state());
    } catch (IdentityAccountException e) {
      return g.currentAccount().map(AccountStatus::state);
    }
  }

  /** Conta restrita: explica e oferece confirmar agora. {@code true} só se a conta ficou ativa. */
  boolean offerConfirmContact(ServerPreset server, String message) {
    IdentityAccountGateway g = gateway(server);
    if (g == null) {
      warn(message);
      return false;
    }
    if (JOptionPane.showConfirmDialog(
            owner,
            WrappedText.message(message + "\n\nConfirmar o e-mail agora?"),
            "Conta MSS restrita",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        != JOptionPane.YES_OPTION) {
      return false;
    }
    Optional<AccountStatus> confirmed = flow(g, server).confirmEmail();
    return confirmed.isPresent() && confirmed.get().state() == AccountState.ACTIVE;
  }

  /**
   * Explica uma recusa de conta sem mascarar a causa (lição BUG-002 do legado do TchowStrick) e,
   * fora de partida ({@code canAct}), oferece a ação que resolve. Devolve {@code true} se a conta
   * desta janela mudou (entrou de novo ou trocou) — quem chama refaz a conexão.
   */
  boolean explainRefusal(ServerPreset server, AccountRefusedException refusal, boolean canAct) {
    if (!canAct || gateway(server) == null) {
      warn(refusal.getMessage());
      return false;
    }
    if (refusal.reason() == AccountRefusedException.Reason.ACCOUNT_RESTRICTED) {
      if (offerConfirmContact(server, refusal.getMessage())) {
        info("E-mail confirmado. Tente de novo.");
      }
      return false;
    }
    if (refusal.requiresSignIn()) {
      if (JOptionPane.showConfirmDialog(
              owner,
              WrappedText.message(refusal.getMessage() + "\n\nEntrar na conta MSS agora?"),
              "Conta MSS",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.WARNING_MESSAGE)
          == JOptionPane.YES_OPTION) {
        return switchAccount(server, false);
      }
      return false;
    }
    warn(refusal.getMessage());
    return false;
  }

  // ------------------------------------------------------------------ fluxos

  /**
   * "Conta MSS…": entrar, ou estado/perfil/sair se já entrou. Devolve {@code true} se a conta desta
   * janela mudou.
   */
  boolean openAccount(ServerPreset server) {
    IdentityAccountGateway g = gateway(server);
    if (g == null) {
      warn(
          "O servidor "
              + (server == null ? "atual" : server.name())
              + " não usa conta MSS. Use Trocar servidor… e escolha um servidor marcado"
              + " \"(conta MSS)\".");
      return false;
    }
    String before = accountIdOf(g);
    MssAccountFlow flow = flow(g, server);
    Optional<AccountStatus> status = flow.refreshStatus();
    if (status.isEmpty()) {
      if (flow.signIn().isPresent()) {
        rememberProfile(g);
      }
      return changed(before, g);
    }
    Optional<IdentityAccountGateway.Profile> profile = flow.profile();
    profile.ifPresent(
        p -> storeOf(g).rememberProfile(status.get().accountId(), p.nick(), p.maskedContact()));
    MssAccountDialog.Result result =
        new MssAccountDialog(
                owner,
                new MssAccountDialog.View(
                    server.name() + " · perfil local " + dataProfile.displayName(),
                    MssAccountFlow.stateMessage(status.get().state()),
                    profile.map(IdentityAccountGateway.Profile::nick).orElse(""),
                    profile.map(IdentityAccountGateway.Profile::avatarId).orElse(""),
                    profile.map(IdentityAccountGateway.Profile::maskedContact).orElse(""),
                    profile.map(IdentityAccountGateway.Profile::contactVerified).orElse(false),
                    profile.isPresent()))
            .showDialog();
    if (result == null) {
      return false;
    }
    switch (result.action()) {
      case SAVE_PROFILE ->
          flow.updateProfile(result.nick(), result.avatarId())
              .ifPresent(
                  p -> {
                    storeOf(g).rememberProfile(p.accountId(), p.nick(), p.maskedContact());
                    info("Perfil da conta MSS atualizado.");
                  });
      case CONFIRM_EMAIL -> flow.confirmEmail();
      case RECOVER -> {
        if (flow.recover().isPresent()) {
          rememberProfile(g);
        }
      }
      case SWITCH_ACCOUNT -> {
        return switchAccount(server, true);
      }
      case SIGN_OUT_THIS_DEVICE -> flow.signOut(false);
      case SIGN_OUT_ALL_DEVICES -> {
        if (JOptionPane.showConfirmDialog(
                owner,
                "Sair da conta MSS em todos os dispositivos (inclusive outros jogos MSS)?",
                "Conta MSS",
                JOptionPane.YES_NO_OPTION)
            == JOptionPane.YES_OPTION) {
          flow.signOut(true);
        }
      }
    }
    return changed(before, g);
  }

  /** Sai só deste perfil local e entra com outra conta MSS. {@code true} se a conta mudou. */
  boolean switchAccount(ServerPreset server, boolean ask) {
    IdentityAccountGateway g = gateway(server);
    if (g == null) {
      warn("Este servidor não usa conta MSS.");
      return false;
    }
    String before = accountIdOf(g);
    if (ask
        && g.currentAccount().isPresent()
        && JOptionPane.showConfirmDialog(
                owner,
                WrappedText.message(
                    "Sair da conta MSS "
                        + label(server)
                        + " nesta janela (perfil local "
                        + dataProfile.displayName()
                        + ") e entrar com outra?"),
                "Trocar de conta",
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) {
      return false;
    }
    if (flow(g, server).switchAccount().isPresent()) {
      rememberProfile(g);
    }
    return changed(before, g);
  }

  /** "Gerenciar contas…": lista e remove dados locais, entra na conta, abre nova janela. */
  boolean manageAccounts(ServerPreset server) {
    IdentityAccountGateway current = gateway(server);
    String before = current == null ? null : accountIdOf(current);
    LocalAccountsService service =
        new LocalAccountsService(DataProfile.defaultDataDir(), dataProfile);
    new ManageAccountsDialog(
            owner,
            new ManageAccountsDialog.Actions() {
              @Override
              public List<LocalAccountsService.Entry> list() {
                return service.list();
              }

              @Override
              public void addMss() {
                addMssFromPanel(server);
              }

              @Override
              public void addWindow() {
                openNewWindowFlow();
              }

              @Override
              public void remove(LocalAccountsService.Entry entry) {
                removeLocalEntry(service, entry);
              }
            })
        .setVisible(true);
    IdentityAccountGateway g = gateway(server);
    return g != null && !Objects.equals(before, accountIdOf(g));
  }

  private void addMssFromPanel(ServerPreset server) {
    IdentityAccountGateway g = gateway(server);
    if (g == null) {
      warn(
          "O servidor atual não usa conta MSS. Use Trocar servidor… e escolha um servidor marcado"
              + " \"(conta MSS)\".");
      return;
    }
    if (g.currentAccount().isEmpty()) {
      if (flow(g, server).signIn().isPresent()) {
        rememberProfile(g);
      }
      return;
    }
    Object[] options = {"Nova janela", "Trocar de conta nesta janela", "Cancelar"};
    int choice =
        JOptionPane.showOptionDialog(
            owner,
            WrappedText.message(
                "Esta janela (perfil local "
                    + dataProfile.displayName()
                    + ") já está na conta MSS "
                    + label(server)
                    + ".\nPara jogar com outra conta ao mesmo tempo, abra uma nova janela: ela usa"
                    + " outro perfil local, com a sua própria conta."),
            "Adicionar conta MSS",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]);
    if (choice == 0) {
      openNewWindow(null);
    } else if (choice == 1) {
      switchAccount(server, false);
    }
  }

  private void openNewWindowFlow() {
    String name =
        JOptionPane.showInputDialog(
            owner,
            "Nome do perfil local da nova janela (vazio = próximo livre, ex.: perfil-2):",
            "Nova janela",
            JOptionPane.PLAIN_MESSAGE);
    if (name == null) {
      return;
    }
    String perfil = null;
    if (!name.isBlank()) {
      try {
        perfil = DataProfile.normalize(name);
      } catch (DataProfileException e) {
        warn(e.getMessage());
        return;
      }
    }
    openNewWindow(perfil);
  }

  /**
   * Abre outro processo do cliente (mesmo Java, classpath e argumentos, outro perfil local). Sem
   * {@code --embedded-server}/{@code --port=}: só uma janela hospeda.
   */
  private void openNewWindow(String perfil) {
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    for (String property :
        List.of("domino.servers.file", DataProfile.DATA_DIR_PROPERTY, "domino.tls.devCaFile")) {
      String value = System.getProperty(property);
      if (value != null) {
        command.add("-D" + property + "=" + value);
      }
    }
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(Main.class.getName());
    launchArgs.stream()
        .filter(
            a ->
                !a.startsWith("--perfil=")
                    && !a.equals("--embedded-server")
                    && !a.startsWith("--port="))
        .forEach(command::add);
    if (perfil != null) {
      command.add("--perfil=" + perfil);
    }
    try {
      new ProcessBuilder(command)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
      info("Abrindo nova janela" + (perfil == null ? "" : " (perfil local " + perfil + ")") + "…");
    } catch (IOException e) {
      warn("Não foi possível abrir outra janela: " + e.getMessage());
    }
  }

  private void removeLocalEntry(LocalAccountsService service, LocalAccountsService.Entry entry) {
    Optional<String> blocker = service.removalBlocker(entry);
    if (blocker.isPresent()) {
      warn(blocker.get());
      return;
    }
    JPanel form = new JPanel(new BorderLayout(0, 8));
    form.add(WrappedText.label(service.removalDescription(entry)), BorderLayout.CENTER);
    JCheckBox remoteSignOut = new JCheckBox("Sair também no servidor (só este dispositivo)", false);
    if (entry.kind() == LocalAccountsService.Kind.MSS) {
      form.add(remoteSignOut, BorderLayout.SOUTH);
    }
    if (JOptionPane.showConfirmDialog(
            owner,
            form,
            "Remover deste computador",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE)
        != JOptionPane.OK_OPTION) {
      return;
    }
    try {
      service
          .remove(
              entry,
              remoteSignOut.isSelected()
                  ? (target, sessionStore, deviceId) -> {
                    try (IdentityClientGateway remote =
                        new IdentityClientGateway(target, sessionStore, deviceId)) {
                      remote.signOut(false);
                    }
                  }
                  : null)
          .ifPresent(this::warn);
    } catch (DataProfileException | IllegalStateException e) {
      warn(e.getMessage());
    }
    evictSignedOut();
  }

  /**
   * Fecha os gateways desta janela cuja sessão local foi apagada (o {@code IdentityClient} guarda a
   * sessão e o acesso de jogo em memória); os demais continuam servindo a conexão aberta.
   */
  private void evictSignedOut() {
    slots
        .entrySet()
        .removeIf(
            e -> {
              if (e.getValue().store().load().isPresent()) {
                return false;
              }
              try {
                e.getValue().gateway().close();
              } catch (RuntimeException ignored) {
                // best-effort
              }
              return true;
            });
  }

  // ------------------------------------------------------------------ util

  private MssAccountFlow flow(IdentityAccountGateway g, ServerPreset server) {
    return new MssAccountFlow(
        g,
        new MssAccountFlow.Prompts() {
          @Override
          public MssAccountFlow.SignInChoice askSignIn() {
            MssSignInDialog.Result r =
                MssSignInDialog.show(
                    owner, server == null ? "?" : server.name(), suggestedNick.get());
            return r == null
                ? null
                : new MssAccountFlow.SignInChoice(r.newAccount(), r.nick(), r.email());
          }

          @Override
          public String askEmail(String title) {
            return JOptionPane.showInputDialog(
                owner, "E-mail da conta MSS:", title, JOptionPane.PLAIN_MESSAGE);
          }

          @Override
          public String askCode(
              String email, IdentityAccountGateway.Purpose purpose, Runnable resend) {
            ConfirmContactCodeDialog dialog = new ConfirmContactCodeDialog(owner, email, resend);
            dialog.setTitle(
                purpose == IdentityAccountGateway.Purpose.RECOVER_ACCOUNT
                    ? "Entrar na conta MSS"
                    : "Confirmar e-mail");
            ConfirmContactCodeDialog.Result result = dialog.showDialog();
            return result == null ? null : result.code();
          }

          @Override
          public boolean confirm(String message) {
            return JOptionPane.showConfirmDialog(
                    owner, WrappedText.message(message), "Conta MSS", JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;
          }

          @Override
          public void info(String message) {
            MssAccountUi.this.info(message);
          }

          @Override
          public void warn(String message) {
            MssAccountUi.this.warn(message);
          }
        });
  }

  /** Lembra nick e contato mascarado da conta para a barra e o painel de contas. */
  private void rememberProfile(IdentityAccountGateway g) {
    try {
      Optional<AccountStatus> account = g.currentAccount();
      IdentitySessionStore store = storeOf(g);
      if (account.isPresent() && store != null) {
        IdentityAccountGateway.Profile p = g.profile();
        store.rememberProfile(account.get().accountId(), p.nick(), p.maskedContact());
      }
    } catch (IdentityAccountException e) {
      // a barra mostra "conectada" sem o nick
    }
  }

  private static String accountIdOf(IdentityAccountGateway g) {
    return g.currentAccount().map(AccountStatus::accountId).orElse(null);
  }

  private static boolean changed(String before, IdentityAccountGateway g) {
    return !Objects.equals(before, accountIdOf(g));
  }

  private void info(String message) {
    LOG.info("conta MSS: {}", message);
    JOptionPane.showMessageDialog(
        owner, WrappedText.message(message), "Conta MSS", JOptionPane.INFORMATION_MESSAGE);
  }

  private void warn(String message) {
    LOG.warn("conta MSS: {}", message);
    JOptionPane.showMessageDialog(
        owner, WrappedText.message(message), "Conta MSS", JOptionPane.WARNING_MESSAGE);
  }

  /** Id abreviado para exibição (nunca o token). */
  private static final class LocalAccountsServiceIds {
    private LocalAccountsServiceIds() {}

    static String shortId(String id) {
      if (id == null || id.isBlank()) {
        return "—";
      }
      return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }
  }
}
