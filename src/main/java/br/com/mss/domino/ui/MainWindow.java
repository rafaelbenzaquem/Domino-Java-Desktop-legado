package br.com.mss.domino.ui;

import br.com.mss.domino.ConnectionResolver;
import br.com.mss.domino.app.ClientEventBus;
import br.com.mss.domino.app.ClientGameView;
import br.com.mss.domino.app.LocalProfileStore;
import br.com.mss.domino.app.LocalServerChoiceStore;
import br.com.mss.domino.app.LocalSessionTokenStore;
import br.com.mss.domino.app.MatchController;
import br.com.mss.domino.app.MatchObserver;
import br.com.mss.domino.app.PlayerProfile;
import br.com.mss.domino.app.ProfileStore;
import br.com.mss.domino.app.ServerChoiceStore;
import br.com.mss.domino.app.SessionTokenStore;
import br.com.mss.domino.domain.End;
import br.com.mss.domino.domain.GameMode;
import br.com.mss.domino.domain.Hand;
import br.com.mss.domino.domain.Line;
import br.com.mss.domino.domain.Seat;
import br.com.mss.domino.domain.Tile;
import br.com.mss.domino.net.DtoMapper;
import br.com.mss.domino.net.GameEvent;
import br.com.mss.domino.net.GameTransport;
import br.com.mss.domino.net.NetworkConfig;
import br.com.mss.domino.net.SessionExpiredException;
import br.com.mss.domino.net.TransportException;
import br.com.mss.domino.net.config.ServerDirectory;
import br.com.mss.domino.net.config.ServerPreset;
import br.com.mss.domino.net.dto.EndReasonDto;
import br.com.mss.domino.net.dto.GameModeDto;
import br.com.mss.domino.net.dto.MatchInfoDto;
import br.com.mss.domino.net.dto.SeatConnectionStatus;
import br.com.mss.domino.net.dto.SeatView;
import br.com.mss.domino.net.dto.TeamDto;
import br.com.mss.domino.net.dto.TileDto;
import br.com.mss.domino.net.grpc.GrpcClientTransport;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Janela principal do cliente (ligada à camada {@code net}/{@code app}). Um {@link CardLayout} com
 * quatro telas: conectar → lista de partidas → lobby → jogo. Crua — o polimento (splash, layouts,
 * ícones) é da Fase 4.
 */
public final class MainWindow extends JFrame implements MatchObserver {

  private static final Logger LOG = LoggerFactory.getLogger(MainWindow.class);

  private static final String CONNECT = "connect";
  private static final String LIST = "list";
  private static final String LOBBY = "lobby";
  private static final String GAME = "game";

  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final ClientEventBus bus = new ClientEventBus();

  private final ProfileStore profileStore = new LocalProfileStore();
  private final ServerChoiceStore serverChoiceStore = new LocalServerChoiceStore();
  private final LocalSessionTokenStore sessionTokenStore = new LocalSessionTokenStore();
  private final ProfileBar profileBar = new ProfileBar();
  private final ServerBar serverBar = new ServerBar();
  private final StatsBar statsBar = new StatsBar();

  private final ConnectCard connectCard = new ConnectCard();
  private final MatchListCard listCard = new MatchListCard();
  private final LobbyCard lobbyCard = new LobbyCard();
  private final GameCard gameCard = new GameCard();

  private MatchController controller;
  // tokens do servidor ao qual o controller está ligado (BUG-009), não do escolhido na barra —
  // com --embedded-server os dois podem ser diferentes
  private SessionTokenStore tokens;
  private PlayerProfile profile;
  private ServerPreset activeServer;
  private String nick = "jogador";
  private final AutoCloseable embeddedServer;
  private boolean inMatch; // LOBBY ou GAME mostrado agora — trava troca de perfil/servidor (4.5-2)

  public MainWindow() {
    this(null, null, null);
  }

  /**
   * {@code autoConnect} não nulo pula a etapa de conectar (ADR-0020): usado pelo {@code
   * --embedded-server} do {@link br.com.mss.domino.Main}, que já sabe o host antes de a janela
   * abrir.
   */
  public MainWindow(NetworkConfig autoConnect) {
    this(autoConnect, null, null);
  }

  /**
   * {@code embeddedServer} é o {@code GrpcHostTransport} que o {@code --embedded-server} subiu
   * neste mesmo processo (ADR-0020) — {@code null} para o cliente puro. Fechar esta janela precisa
   * encerrar tanto a sessão de rede do cliente quanto esse host embarcado, senão o processo fica
   * vivo em segundo plano (o gRPC mantém threads não-daemon enquanto o servidor estiver no ar) —
   * daí o {@link #dispose()} chamar {@link System#exit} no final. {@code cliServer} é o {@code
   * --server=host:porta} da linha de comando (ADR-0022), se houver — só pré-seleciona o servidor da
   * {@code ConnectCard}, não afeta {@code autoConnect}.
   */
  public MainWindow(
      NetworkConfig autoConnect, AutoCloseable embeddedServer, ServerPreset cliServer) {
    super("Dominó");
    this.embeddedServer = embeddedServer;
    this.activeServer =
        ConnectionResolver.resolveDefault(cliServer, ServerDirectory.load(), serverChoiceStore);
    setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    // Fase 4.5-2: uma inscrição só, para a vida inteira da janela — chamar de novo em
    // connectTo()/reconnectTo() (jeito antigo) inscrevia esta janela mais uma vez a cada
    // (re)conexão, sem nunca desinscrever; dali pra frente, todo evento (não só o de fim de
    // jogo) era entregue em dobro, piorando a cada reconexão adicional.
    bus.subscribe(this);
    deck.add(connectCard, CONNECT);
    deck.add(listCard, LIST);
    deck.add(lobbyCard, LOBBY);
    deck.add(gameCard, GAME);
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.add(profileBar);
    top.add(serverBar);
    top.add(statsBar);
    JPanel root = new JPanel(new BorderLayout());
    root.add(top, BorderLayout.NORTH);
    root.add(deck, BorderLayout.CENTER);
    setContentPane(root);
    setPreferredSize(new Dimension(1000, 640));
    pack();
    setLocationRelativeTo(null);
    ensureProfile();
    showCard(CONNECT);
    if (autoConnect != null) {
      connectTo(autoConnect, false); // embarcado (ADR-0020): sempre texto puro
    }
  }

  /** Primeira execução: sem nenhum perfil salvo, força a criação de um (ADR-0018). */
  private void ensureProfile() {
    if (profileStore.list().isEmpty()) {
      new ProfileDialog(this, profileStore).showDialog();
    }
    refreshActiveProfile();
  }

  private void refreshActiveProfile() {
    profile = profileStore.active().orElse(null);
    nick = profile == null ? "jogador" : profile.displayName();
    // Escopa o LocalSessionTokenStore ao perfil ativo (ADR-0018) -- sem isto, dois clientes na
    // mesma conta do Windows compartilham o mesmo Preferences e "Retomar" não distingue de quem é
    // cada token salvo (achado em teste manual, 18/09/2026).
    if (profile != null) {
      sessionTokenStore.useProfile(profile.id());
    }
    profileBar.update(profile, !inMatch);
  }

  /**
   * Encerra a sessão de rede do cliente e (se houver) o servidor embarcado, e derruba a JVM. {@code
   * System.exit} é deliberado (não "deixa o JVM sair sozinho"): garante o fim do processo mesmo que
   * sobre alguma thread não-daemon do RMI que o unexport não tenha alcançado a tempo — item
   * reportado na validação da fase-3c-2 (processo ficava vivo em segundo plano ao fechar a janela,
   * tanto como cliente puro quanto como host embarcado).
   */
  @Override
  public void dispose() {
    if (controller != null) {
      controller.close();
    }
    if (embeddedServer != null) {
      try {
        embeddedServer.close();
      } catch (Exception e) {
        LOG.warn("falha ao encerrar o servidor embarcado", e);
      }
    }
    super.dispose();
    System.exit(0);
  }

  /**
   * Fase 4.5-2: só {@code LOBBY}/{@code GAME} travam a troca de perfil/servidor — conectado mas só
   * navegando ({@code CONNECT}/{@code LIST}) continua trocável, mesmo no {@code --embedded-server}
   * (que já entra direto na lista: antes, o gate era {@code controller == null}, que essa conexão
   * automática já deixa falso antes de o jogador ter qualquer chance de clicar).
   */
  private void showCard(String card) {
    LOG.debug("tela -> {}", card);
    cards.show(deck, card);
    inMatch = LOBBY.equals(card) || GAME.equals(card);
    profileBar.update(profile, !inMatch);
    serverBar.update(activeServer, !inMatch);
  }

  private void error(String message) {
    LOG.warn("UI: {}", message);
    JOptionPane.showMessageDialog(this, message, "Ops", JOptionPane.WARNING_MESSAGE);
  }

  // --- Observer: navegação + repasse para as telas ---

  @Override
  public void onMatchChanged(GameEvent event, ClientGameView view) {
    lobbyCard.render(view);
    gameCard.render(view);
    if (event instanceof GameEvent.MatchStarted) {
      gameCard.resilience.reset(view.mode());
      gameCard.board.setLastPlaced(null); // partida nova: nenhum halo herdado da anterior
      showCard(GAME);
    }
    if (event instanceof GameEvent.MoveApplied moveApplied) {
      // Fase 4-3 (halo na última peça): a jogada de qualquer assento, não só a própria — o board
      // não sabe "de quem" foi, só qual peça acabou de entrar na cadeia.
      gameCard.board.setLastPlaced(DtoMapper.tile(moveApplied.tile()));
    }
    if (event instanceof GameEvent.ChatPosted chat) {
      gameCard.chat.append(chat.chat());
    }
    if (event instanceof GameEvent.MatchEnded) {
      error(gameCard.outcomeText(view));
      gameCard.setRematchVoteActive(false, null); // tela de fim de jogo começa sem votação em curso
    }
    if (event instanceof GameEvent.RematchRequested requested) {
      gameCard.setRematchVoteActive(true, view.nameOf(requested.requestedBySeat()));
    }
    if (event instanceof GameEvent.RematchDeclined) {
      gameCard.setRematchVoteActive(false, null);
    }
    if (event instanceof GameEvent.RematchStarted rematch) {
      // Fase 4.5-4 (ADR-0024): o host já criou a partida da revanche — cada cliente entra nela
      // sozinho, o mesmo caminho de qualquer partida da lista (nenhuma seleção de assento aqui).
      try {
        controller.join(rematch.newMatchId(), nick);
        showCard(LOBBY);
      } catch (TransportException e) {
        error("falha ao entrar na revanche: " + e.getMessage());
      }
    }
    if (event != null) {
      gameCard.resilience.onEvent(event);
    }
  }

  // ================= Barra de perfil (ADR-0018) =================

  /** Persistente acima de todas as telas: "Perfil: `<nome>` [Trocar perfil…]". */
  private final class ProfileBar extends JPanel {

    private final JLabel label = new JLabel();
    private final JButton change = new JButton("Trocar perfil…");

    ProfileBar() {
      super(new FlowLayout(FlowLayout.LEFT, 8, 4));
      setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
      add(label);
      add(change);
      change.addActionListener(
          e -> {
            new ProfileDialog(MainWindow.this, profileStore).showDialog();
            refreshActiveProfile();
          });
    }

    /**
     * {@code canChange} é falso só durante uma partida (lobby ou jogo, Fase 4.5-2) — conectado mas
     * só navegando ({@code CONNECT}/{@code LIST}) continua trocável, {@code --embedded-server}
     * incluso.
     */
    void update(PlayerProfile p, boolean canChange) {
      label.setText("Perfil: " + (p == null ? "?" : p.displayName()));
      change.setEnabled(canChange);
    }
  }

  // ================= Barra de servidor (ADR-0022) =================

  /** Persistente acima de todas as telas: "Servidor: `<nome>` [Trocar servidor…]". */
  private final class ServerBar extends JPanel {

    private final JLabel label = new JLabel();
    private final JButton change = new JButton("Trocar servidor…");

    ServerBar() {
      super(new FlowLayout(FlowLayout.LEFT, 8, 0));
      setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
      add(label);
      add(change);
      change.addActionListener(e -> switchServerFlow());
    }

    /**
     * {@code canChange} é falso só durante uma partida (lobby ou jogo, Fase 4.5-2) — conectado mas
     * só navegando ({@code CONNECT}/{@code LIST}) continua trocável, {@code --embedded-server}
     * incluso.
     */
    void update(ServerPreset server, boolean canChange) {
      label.setText(
          "Servidor: " + (server == null ? "?" : server.name() + (server.tls() ? " [TLS]" : "")));
      change.setEnabled(canChange);
    }
  }

  /** Abre o {@link ServerPickerDialog} e grava a escolha, se houver (só fora de partida). */
  private void switchServerFlow() {
    ServerPreset chosen = new ServerPickerDialog(this, activeServer).showDialog();
    if (chosen != null) {
      activeServer = chosen;
      serverChoiceStore.remember(chosen);
      serverBar.update(activeServer, !inMatch);
    }
  }

  // ================= Estatísticas e ranking (M5-5, ADR-0029) =================

  /** Persistente acima de todas as telas: um botão só, disponível a qualquer momento conectado. */
  private final class StatsBar extends JPanel {

    private final JButton open = new JButton("Estatísticas/Ranking…");

    StatsBar() {
      super(new FlowLayout(FlowLayout.LEFT, 8, 0));
      setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
      add(open);
      open.addActionListener(e -> openStats());
    }
  }

  /**
   * Não trava com "canChange" como profileBar/serverBar (Fase 4.5-2) — consultar estatísticas é só
   * leitura e não interfere na partida em curso, então continua disponível em LOBBY/GAME também.
   * Sem partida ativa ({@code controller.view() == null}), abre no modo "um contra todos" por
   * padrão.
   */
  private void openStats() {
    if (controller == null) {
      error("desconectado — volte a conectar");
      return;
    }
    ClientGameView view = controller.view();
    GameMode mode = view == null ? GameMode.FREE_FOR_ALL : view.mode();
    new StatsDialog(this, controller, guestId(), mode).showDialog();
  }

  // ================= Conectar =================

  private final class ConnectCard extends JPanel {

    ConnectCard() {
      super(new BorderLayout());
      add(new SplashPanel(), BorderLayout.CENTER); // Fase 4-4: tela inicial do Main

      JPanel form = new JPanel(new GridBagLayout());
      JTextField tokenField = new JTextField(16);
      JButton connect = new JButton("Conectar");
      JButton reconnect = new JButton("Reconectar com esse token");

      GridBagConstraints c = new GridBagConstraints();
      c.insets = new Insets(6, 6, 6, 6);
      c.gridx = 1;
      c.gridy = 0;
      form.add(connect, c);
      c.gridx = 0;
      c.gridy = 1;
      c.anchor = GridBagConstraints.LINE_END;
      form.add(new JLabel("Token de sessão (opcional):"), c);
      c.gridx = 1;
      c.anchor = GridBagConstraints.LINE_START;
      form.add(tokenField, c);
      c.gridx = 1;
      c.gridy = 2;
      form.add(reconnect, c);
      add(form, BorderLayout.SOUTH);

      connect.addActionListener(e -> connectTo(activeServerConfig(), activeServer.tls()));
      reconnect.addActionListener(
          e -> {
            String token = tokenField.getText().trim();
            if (token.isEmpty()) {
              error("cole o token de sessão mostrado na janela do jogo, antes de cair");
              return;
            }
            reconnectTo(activeServerConfig(), activeServer.tls(), token);
          });
    }
  }

  private NetworkConfig activeServerConfig() {
    return new NetworkConfig(activeServer.host(), activeServer.port());
  }

  /** {@code PlayerId} do perfil ativo (ADR-0018), auto-declarado em Join como guest_id (M5-4). */
  private String guestId() {
    return profile == null ? null : profile.id().value();
  }

  private void connectTo(NetworkConfig config, boolean tls) {
    try {
      GrpcClientTransport transport =
          new GrpcClientTransport(config.host(), config.port(), guestId(), tls);
      tokens = sessionTokenStore.forServer(config.host(), config.port());
      controller = new MatchController(transport, bus, tokens);
      controller.open();
      listCard.refresh();
      showCard(LIST); // já atualiza profileBar/serverBar (Fase 4.5-2)
    } catch (TransportException | RuntimeException e) {
      controller = null;
      error("não conectou: " + e.getMessage());
    }
  }

  /**
   * Reconecta direto a uma partida em andamento pelo {@code session_token} de uma sessão anterior
   * (ADR-0008, ADR-0013) — pula a lista, já que o token identifica a partida e o assento. Chamada
   * tanto pelo campo avançado/manual da {@code ConnectCard} quanto pela oferta automática de
   * retomada da {@code MatchListCard} (ADR-0019).
   */
  private void reconnectTo(NetworkConfig config, boolean tls, String token) {
    try {
      GrpcClientTransport transport =
          new GrpcClientTransport(config.host(), config.port(), guestId(), tls);
      tokens = sessionTokenStore.forServer(config.host(), config.port());
      controller = new MatchController(transport, bus, tokens);
      controller.open();
      // Lê o modo do snapshot devolvido aqui (síncrono), não de controller.view() — este só é
      // populado quando o SwingUtilities.invokeLater de MatchController.seedView() roda, o que
      // nunca acontece antes do fim deste método (já estamos na EDT). Lido direto, sempre dava
      // NullPointerException, disfarçada de falha de reconexão pelo catch abaixo (Fase 5-1).
      showResumed(controller.reconnect(token));
    } catch (TransportException | RuntimeException e) {
      controller = null;
      error("não reconectou: " + e.getMessage());
      showCard(CONNECT); // não deixa o app preso numa tela com controller == null (Fase 5-1)
    }
  }

  /** Exibe o snapshot síncrono da retomada, inclusive quando a partida ainda está no lobby. */
  private void showResumed(GameTransport.Session session) {
    // "Entrar" na MatchListCard (ADR-0019) usa este mesmo caminho pra qualquer token salvo, não
    // só o de uma queda em partida já em andamento -- inclui reabrir o cliente ainda no lobby
    // (join() já salva o token na hora, antes de a partida começar). Sem checar a fase aqui,
    // reconectar a um lobby jogava a tela direto pro jogo, com o board vazio.
    if ("LOBBY".equals(session.snapshot().phase())) {
      showCard(LOBBY);
    } else {
      gameCard.resilience.reset(DtoMapper.mode(session.snapshot().mode()));
      showCard(GAME); // já atualiza profileBar/serverBar (Fase 4.5-2)
    }
  }

  /** Token de um assento do perfil ativo no servidor conectado (ADR-0019, BUG-009). */
  private record SavedSeat(int seat, String token) {}

  // ================= Lista de partidas =================

  private final class MatchListCard extends JPanel {

    private final DefaultListModel<MatchInfoDto> model = new DefaultListModel<>();
    private final JList<MatchInfoDto> list = new JList<>(model);
    private final JButton enter = new JButton("Entrar");

    // Fase 4-1 (ADR-0014): lista "ao vivo" por polling leve — fallback aceito no lugar de um
    // Observer de lobby de verdade (esse exigiria um canal de eventos antes de entrar numa
    // partida, fora de escopo aqui). Só atualiza quando este card está de fato visível.
    private final Timer liveRefresh =
        new Timer(
            4000,
            e -> {
              if (controller != null && isShowing()) {
                refresh();
              }
            });

    MatchListCard() {
      super(new BorderLayout(8, 8));
      setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

      list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      list.setCellRenderer(
          (jlist, value, index, selected, focus) -> {
            String text =
                "%s%s%s — %s — %d/%d — %s — host: %s"
                    .formatted(
                        value.hasPassword() ? "🔒 " : "",
                        hasSavedToken(value) ? "↩ " : "",
                        value.name(),
                        modeLabel(value.mode()),
                        value.joined(),
                        value.size(),
                        phaseLabel(value.phase()),
                        value.host());
            JLabel label = new JLabel(text);
            label.setOpaque(true);
            if (selected) {
              label.setBackground(jlist.getSelectionBackground());
              label.setForeground(jlist.getSelectionForeground());
            }
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return label;
          });
      list.addListSelectionListener(e -> enter.setEnabled(list.getSelectedValue() != null));
      enter.setEnabled(false);

      JButton refresh = new JButton("Atualizar");
      JButton create = new JButton("Criar partida");
      JButton back = new JButton("Voltar");
      JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
      actions.add(refresh);
      actions.add(create);
      actions.add(enter);
      actions.add(back);

      add(new JLabel("Partidas no servidor"), BorderLayout.NORTH);
      add(new JScrollPane(list), BorderLayout.CENTER);
      add(actions, BorderLayout.SOUTH);

      refresh.addActionListener(e -> refresh());
      create.addActionListener(e -> createMatch());
      enter.addActionListener(e -> joinSelected());
      back.addActionListener(e -> showCard(CONNECT));

      liveRefresh.start();
    }

    void refresh() {
      try {
        List<MatchInfoDto> matches = controller.listMatches();
        MatchInfoDto previouslySelected = list.getSelectedValue();
        model.clear();
        matches.forEach(model::addElement);
        // Reseleciona o mesmo item (por id, a instância do DTO troca a cada refresh) em vez de
        // perder a seleção do jogador a cada 4s de liveRefresh (ADR-0014).
        if (previouslySelected != null) {
          selectById(previouslySelected.matchId());
        } else {
          autoSelectResumable(matches);
        }
      } catch (TransportException e) {
        error("falha ao listar: " + e.getMessage());
      }
    }

    private void selectById(String matchId) {
      for (int i = 0; i < model.size(); i++) {
        if (model.get(i).matchId().equals(matchId)) {
          list.setSelectedIndex(i);
          return;
        }
      }
    }

    /**
     * ADR-0019: se exatamente uma partida da lista tem um {@code session_token} salvo do perfil
     * ativo ({@link LocalSessionTokenStore#useProfile}), já deixa selecionada -- o jogador só
     * confirma com "Entrar" (ver {@link #joinSelected()}), sem precisar reconhecer sua própria mesa
     * entre as outras. Com mais de uma (raro: perfil com partidas paralelas em andamento), não
     * arrisca escolher errado por ele -- fica sem seleção, do jeito de sempre.
     */
    private void autoSelectResumable(List<MatchInfoDto> matches) {
      MatchInfoDto onlyResumable = null;
      for (MatchInfoDto match : matches) {
        if (hasSavedToken(match)) {
          if (onlyResumable != null) {
            return; // mais de uma -- não escolhe por ele
          }
          onlyResumable = match;
        }
      }
      if (onlyResumable != null) {
        selectById(onlyResumable.matchId());
      }
    }

    /** Algum assento desta partida tem {@code session_token} salvo do perfil ativo (ADR-0019)? */
    private boolean hasSavedToken(MatchInfoDto match) {
      return findSavedToken(match).isPresent();
    }

    private Optional<SavedSeat> findSavedToken(MatchInfoDto match) {
      if (tokens == null) {
        return Optional.empty();
      }
      for (int seat = 0; seat < match.size(); seat++) {
        Optional<String> token = tokens.find(match.matchId(), seat);
        if (token.isPresent()) {
          return Optional.of(new SavedSeat(seat, token.get()));
        }
      }
      return Optional.empty();
    }

    private void createMatch() {
      if (controller == null) {
        error("desconectado — volte a conectar");
        showCard(CONNECT);
        return;
      }
      Optional<CreateMatchDialog.Result> choice =
          new CreateMatchDialog(MainWindow.this, "Mesa de " + nick).prompt();
      if (choice.isEmpty()) {
        return;
      }
      CreateMatchDialog.Result r = choice.get();
      try {
        String id =
            controller.createMatch(
                r.name(), r.mode(), r.size(), r.turnSeconds(), r.timeBankSeconds(), r.password());
        controller.join(id, nick, r.password()); // a própria senha que acabou de escolher
        showCard(LOBBY);
      } catch (TransportException e) {
        error("falha ao criar: " + e.getMessage());
      }
    }

    private void joinSelected() {
      if (controller == null) {
        error("desconectado — volte a conectar");
        showCard(CONNECT);
        return;
      }
      MatchInfoDto selected = list.getSelectedValue();
      if (selected == null) {
        return;
      }

      // ADR-0019: perfil ativo já esteve nesta partida (join/reconnect anterior salvou o
      // token) -- reconecta direto, sem pedir senha de novo nem tentar um join novo (o servidor
      // recusaria com "partida já começou"). É a única ação: selecionar a partida e "Entrar".
      // BUG-009: pela conexão já aberta (o token só vale no servidor que o emitiu); se o host não
      // conhece mais a sessão, o token é de outra partida com o mesmo id -- descarta e segue para
      // a entrada normal. Queda de rede mantém o token e só reporta o erro.
      for (Optional<SavedSeat> saved = findSavedToken(selected);
          saved.isPresent();
          saved = findSavedToken(selected)) {
        try {
          showResumed(controller.reconnect(saved.get().token()));
          return;
        } catch (SessionExpiredException e) {
          LOG.info(
              "token salvo de {} assento {} recusado pelo host ({}) -- descartado",
              selected.matchId(),
              saved.get().seat(),
              e.getMessage());
          tokens.remove(selected.matchId(), saved.get().seat());
        } catch (TransportException e) {
          error("não reconectou: " + e.getMessage());
          return;
        }
      }

      String password = "";
      if (selected.hasPassword()) {
        Optional<String> typed = askPassword();
        if (typed.isEmpty()) {
          return; // cancelou o diálogo de senha
        }
        password = typed.get();
      }
      try {
        controller.join(selected.matchId(), nick, password);
        showCard(LOBBY);
      } catch (TransportException e) {
        error("falha ao entrar: " + e.getMessage());
      }
    }

    /** ADR-0014 (Fase 4-1): pede a senha antes de "Entrar" numa partida 🔒. Vazio se cancelou. */
    private Optional<String> askPassword() {
      JPasswordField field = new JPasswordField(16);
      int choice =
          JOptionPane.showConfirmDialog(
              MainWindow.this,
              field,
              "Senha da partida",
              JOptionPane.OK_CANCEL_OPTION,
              JOptionPane.PLAIN_MESSAGE);
      return choice == JOptionPane.OK_OPTION
          ? Optional.of(new String(field.getPassword()))
          : Optional.empty();
    }
  }

  // ================= Lobby =================

  private final class LobbyCard extends JPanel {

    private static final int AUTO_START_COUNTDOWN_SECONDS = 3;

    private final JLabel title = new JLabel("Sala de espera", SwingConstants.CENTER);
    private final JPanel body = new JPanel(new GridBagLayout());
    private final JButton startButton = new JButton("Começar");
    private final Timer countdownTimer = new Timer(1000, e -> tickCountdown());
    private int countdownRemaining;
    private boolean wasStartable;

    LobbyCard() {
      super(new BorderLayout(10, 10));
      setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
      title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
      add(title, BorderLayout.NORTH);
      add(body, BorderLayout.CENTER);
      JPanel south = new JPanel();
      south.add(startButton);
      add(south, BorderLayout.SOUTH);
      startButton.addActionListener(
          e -> {
            countdownTimer.stop();
            try {
              controller.startMatch();
            } catch (TransportException ex) {
              error("falha ao iniciar: " + ex.getMessage());
            }
          });
    }

    void render(ClientGameView view) {
      if (view == null || view.phase() != ClientGameView.Phase.LOBBY) {
        return;
      }
      title.setText("Sala de espera — " + view.matchId());
      body.removeAll();
      if (view.mode() == GameMode.PARTNERSHIP) {
        renderPartnership(view);
      } else {
        renderFreeForAll(view);
        stopCountdown(); // sem "pronto" no modo A — enche e começa sozinho (ADR-0011), sem drama
      }
      startButton.setEnabled(view.startable());
      body.revalidate();
      body.repaint();
    }

    private void renderFreeForAll(ClientGameView view) {
      GridBagConstraints c = baseConstraints();
      for (var s : view.seats()) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.add(seatIcon(s.seat()));
        row.add(
            new JLabel(
                "cadeira "
                    + (s.seat() + 1)
                    + ": "
                    + s.name()
                    + (s.seat() == view.mySeat() ? " (você)" : "")));
        body.add(row, c);
        c.gridy++;
      }
      startButton.setText(
          view.startable()
              ? "Começar (" + view.seats().size() + " jogadores)"
              : "Aguardando jogadores… (" + view.seats().size() + ")");
    }

    private void renderPartnership(ClientGameView view) {
      GridBagConstraints c = baseConstraints();
      body.add(boldLabel("Dupla Ímpar — cadeiras 1 e 3    ×    Dupla Par — cadeiras 2 e 4"), c);
      c.gridy++;
      for (var chair : view.seats()) {
        body.add(chairRow(view, chair), c);
        c.gridy++;
      }
      if (!view.standing().isEmpty()) {
        body.add(new JLabel("De pé: " + String.join(", ", view.standing())), c);
        c.gridy++;
      }
      handleAutoStartCountdown(view.startable());
      if (!countdownTimer.isRunning()) {
        startButton.setText(view.startable() ? "Começar" : "Aguardando 4 sentados + 4 prontos…");
      }
    }

    /** Fase 4-2: "Começar em N…" sozinho assim que os 4 ficam prontos — clicar pula a espera. */
    private void handleAutoStartCountdown(boolean startable) {
      if (startable && !wasStartable) {
        countdownRemaining = AUTO_START_COUNTDOWN_SECONDS;
        startButton.setText("Todos prontos! Começando em " + countdownRemaining + "…");
        countdownTimer.restart();
      } else if (!startable) {
        stopCountdown();
      }
      wasStartable = startable;
    }

    private void stopCountdown() {
      countdownTimer.stop();
      wasStartable = false;
    }

    private void tickCountdown() {
      countdownRemaining--;
      if (countdownRemaining <= 0) {
        countdownTimer.stop();
        try {
          controller.startMatch();
        } catch (TransportException ex) {
          error("falha ao iniciar: " + ex.getMessage());
        }
        return;
      }
      startButton.setText("Todos prontos! Começando em " + countdownRemaining + "…");
    }

    /**
     * Bolinha colorida na cor do assento (mesma paleta do {@link BoardView} e dos selos da mesa).
     */
    private JLabel seatIcon(int seat) {
      JLabel icon = new JLabel("●");
      icon.setForeground(PlayerColors.of(Seat.of(seat)));
      return icon;
    }

    private JPanel chairRow(ClientGameView view, br.com.mss.domino.net.dto.SeatView chair) {
      boolean mine = chair.seat() == view.mySeat();
      boolean empty = !chair.occupied();
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
      row.add(seatIcon(chair.seat()));
      row.add(
          new JLabel(
              "Cadeira "
                  + (chair.seat() + 1)
                  + ": "
                  + (empty ? "(livre)" : chair.name())
                  + (mine ? " (você)" : "")
                  + (chair.ready() ? "  ✔ pronto" : "")));
      if (empty && view.mySeat() < 0) {
        JButton sit = new JButton("Sentar");
        sit.addActionListener(e -> lobby(() -> controller.takeChair(chair.seat())));
        row.add(sit);
      } else if (empty) {
        JButton move = new JButton("Mudar para aqui");
        move.addActionListener(e -> lobby(() -> controller.takeChair(chair.seat())));
        row.add(move);
      } else if (mine) {
        JButton ready = new JButton(chair.ready() ? "Cancelar pronto" : "Pronto");
        ready.addActionListener(e -> lobby(() -> controller.setReady(!chair.ready())));
        JButton stand = new JButton("Levantar");
        stand.addActionListener(e -> lobby(() -> controller.leaveChair()));
        row.add(ready);
        row.add(stand);
      }
      return row;
    }

    private void lobby(LobbyAction action) {
      try {
        action.run();
      } catch (TransportException ex) {
        error(ex.getMessage());
      }
    }

    private GridBagConstraints baseConstraints() {
      GridBagConstraints c = new GridBagConstraints();
      c.insets = new Insets(4, 4, 4, 4);
      c.gridx = 0;
      c.gridy = 0;
      c.anchor = GridBagConstraints.LINE_START;
      return c;
    }

    private JLabel boldLabel(String text) {
      JLabel l = new JLabel(text);
      l.setFont(l.getFont().deriveFont(Font.BOLD));
      return l;
    }
  }

  @FunctionalInterface
  private interface LobbyAction {
    void run() throws TransportException;
  }

  // ================= Jogo =================

  private final class GameCard extends JPanel {

    private final BoardView board = new BoardView();
    private final HandPanel hand = new HandPanel();
    private final ChatPanel chat = new ChatPanel();
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel boneyardLabel = new JLabel(" ");
    private final TurnBanner turnBanner = new TurnBanner();
    private final PersonalClockPanel myClock = new PersonalClockPanel();
    private final JButton passButton = new JButton("Passar");
    private final ResiliencePanel resilience = new ResiliencePanel();

    // Fase 4.5-5 (ADR-0025): mesa posicional — um selo por assento adversário ao redor do
    // tabuleiro, no lugar do antigo PlayersPanel (coluna à parte). Você está sempre embaixo.
    private final PlayerBadge topBadge = new PlayerBadge();
    private final PlayerBadge leftBadge = new PlayerBadge();
    private final PlayerBadge rightBadge = new PlayerBadge();

    // Fase 4.5-4 (ADR-0024): só aparece com a partida terminada (view.isFinished()).
    private final JPanel postMatchBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
    private final JButton backButton = new JButton("Voltar para a lista");
    private final JButton rematchButton = new JButton("Pedir revanche");
    private final JButton rematchAccept = new JButton("Aceitar revanche");
    private final JButton rematchDecline = new JButton("Recusar revanche");
    private final JLabel rematchStatus = new JLabel(" ");

    private transient Tile selected;

    GameCard() {
      super(new BorderLayout(6, 6));
      status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));
      status.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
      passButton.setVisible(false);
      passButton.addActionListener(
          e -> {
            try {
              controller.pass();
            } catch (TransportException ex) {
              error("falha ao passar: " + ex.getMessage());
            }
          });
      boneyardLabel.setFont(boneyardLabel.getFont().deriveFont(Font.ITALIC));
      boneyardLabel.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 0));
      boneyardLabel.setVisible(false);
      JPanel north = new JPanel(new BorderLayout());
      north.add(boneyardLabel, BorderLayout.WEST);
      north.add(status, BorderLayout.CENTER);
      north.add(passButton, BorderLayout.EAST);
      JPanel northArea = new JPanel(new BorderLayout());
      northArea.add(north, BorderLayout.NORTH);
      northArea.add(resilience, BorderLayout.SOUTH);

      // acima da mesa: quem está à sua frente (Fase 4.5-5), de quem é a vez, cooldown e banco.
      JPanel tableNorth = new JPanel(new BorderLayout());
      tableNorth.add(centered(topBadge), BorderLayout.NORTH);
      tableNorth.add(turnBanner, BorderLayout.SOUTH);
      JPanel table = new JPanel(new BorderLayout());
      table.add(tableNorth, BorderLayout.NORTH);
      table.add(centered(leftBadge), BorderLayout.WEST);
      table.add(board, BorderLayout.CENTER);
      table.add(centered(rightBadge), BorderLayout.EAST);
      // abaixo da mão: informação própria (meu banco, meu cooldown quando é a minha vez).
      JPanel handArea = new JPanel(new BorderLayout());
      handArea.add(wrapFelt(hand), BorderLayout.CENTER);
      handArea.add(myClock, BorderLayout.SOUTH);
      table.add(handArea, BorderLayout.SOUTH);

      JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, table, chat);
      center.setResizeWeight(0.78);

      postMatchBar.add(backButton);
      postMatchBar.add(rematchButton);
      postMatchBar.add(rematchAccept);
      postMatchBar.add(rematchDecline);
      postMatchBar.add(rematchStatus);
      postMatchBar.setVisible(false);
      backButton.addActionListener(
          e -> {
            controller.leaveMatch();
            showCard(LIST);
            listCard.refresh();
          });
      rematchButton.addActionListener(e -> guarded(controller::requestRematch));
      rematchAccept.addActionListener(e -> guarded(controller::confirmRematch));
      rematchDecline.addActionListener(e -> guarded(controller::declineRematch));

      add(northArea, BorderLayout.NORTH);
      add(center, BorderLayout.CENTER);
      add(postMatchBar, BorderLayout.SOUTH);

      resilience.setActions(
          new ResiliencePanel.Actions() {
            @Override
            public void requestElimination(int seat) throws TransportException {
              controller.requestElimination(seat);
            }

            @Override
            public void confirmElimination() throws TransportException {
              controller.confirmElimination();
            }

            @Override
            public void declineElimination() throws TransportException {
              controller.declineElimination();
            }

            @Override
            public void requestAbort() throws TransportException {
              controller.requestAbort();
            }

            @Override
            public void confirmAbort() throws TransportException {
              controller.confirmAbort();
            }

            @Override
            public void declineAbort() throws TransportException {
              controller.declineAbort();
            }
          });
      resilience.setOnError(MainWindow.this::error);

      hand.setOnSelect(
          tile -> {
            selected = tile;
            board.setHoverCandidate(tile);
          });
      board.addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              tryPlay(e);
            }
          });
      chat.setOnSend(
          text -> {
            try {
              controller.chat(text);
            } catch (TransportException ex) {
              error("falha no chat: " + ex.getMessage());
            }
          });
    }

    private JComponent wrapFelt(JComponent inner) {
      JPanel felt = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
      felt.setBackground(new Color(0x2E, 0x6B, 0x4F));
      felt.add(inner);
      return felt;
    }

    /**
     * Centraliza {@code inner} nas duas direções (usado pros selos de assento ao redor da mesa).
     */
    private JComponent centered(JComponent inner) {
      JPanel wrap = new JPanel(new GridBagLayout());
      wrap.add(inner);
      return wrap;
    }

    /**
     * Fase 4.5-5 (ADR-0025): onde o assento {@code seat} aparece pra quem está sentado em {@code
     * mySeat}, numa mesa de {@code size} lugares — você sempre embaixo (sem selo), os demais em
     * sentido horário a partir de você.
     */
    private SeatPosition positionOf(int seat, int mySeat, int size) {
      int rel = Math.floorMod(seat - mySeat, size);
      return switch (size) {
        case 2 -> SeatPosition.TOP;
        case 3 -> rel == 1 ? SeatPosition.RIGHT : SeatPosition.LEFT;
        default ->
            switch (rel) {
              case 1 -> SeatPosition.RIGHT;
              case 2 -> SeatPosition.TOP;
              default -> SeatPosition.LEFT;
            };
      };
    }

    private enum SeatPosition {
      TOP,
      LEFT,
      RIGHT
    }

    /**
     * Fase 4.5-4: mostra/esconde "Pedir revanche" vs. "Aceitar"/"Recusar" (mesmo padrão do {@link
     * ResiliencePanel} pra aborto/eliminação — quem pediu também vê os dois últimos).
     */
    void setRematchVoteActive(boolean active, String requestedByName) {
      rematchButton.setVisible(!active);
      rematchAccept.setVisible(active);
      rematchDecline.setVisible(active);
      rematchStatus.setText(active ? requestedByName + " pediu revanche" : " ");
    }

    private void guarded(LobbyAction action) {
      try {
        action.run();
      } catch (TransportException ex) {
        error(ex.getMessage());
      }
    }

    void render(ClientGameView view) {
      if (view == null || view.phase() == ClientGameView.Phase.LOBBY) {
        return;
      }
      Line line = DtoMapper.line(view.line());
      Tile opening = view.openingTile() == null ? null : DtoMapper.tile(view.openingTile());
      board.setBoard(line, opening);
      board.setInteractive(interactive(view));

      Hand myHand = DtoMapper.hand(view.myHand());
      hand.setHand(myHand);
      hand.setPlayable(t -> view.isMyTurn() && view.canPlay(DtoMapper.tile(t)));

      topBadge.setVisible(false);
      leftBadge.setVisible(false);
      rightBadge.setVisible(false);
      int size = view.seats().size();
      for (SeatView seat : view.seats()) {
        if (seat.seat() == view.mySeat()) {
          continue;
        }
        boolean isTurn =
            view.phase() == ClientGameView.Phase.IN_PROGRESS && seat.seat() == view.currentSeat();
        PlayerBadge badge =
            switch (positionOf(seat.seat(), view.mySeat(), size)) {
              case TOP -> topBadge;
              case LEFT -> leftBadge;
              case RIGHT -> rightBadge;
            };
        badge.update(seat, isTurn);
      }
      boneyardLabel.setVisible(view.mode() != GameMode.PARTNERSHIP);
      boneyardLabel.setText("dorme: " + view.boneyardCount());

      boolean mustPass =
          view.isMyTurn() && !view.canPlayAny() && view.mode() == GameMode.PARTNERSHIP;
      passButton.setVisible(mustPass);

      if (view.hasActiveClock()) {
        turnBanner.update(
            view.nameOf(view.currentSeat()), view.turnDeadline(), view.turnBankRemaining());
      } else {
        turnBanner.clear();
      }
      myClock.update(
          view.bankOf(view.mySeat()),
          view.isMyTurn(),
          view.turnDeadline(),
          view.turnBankRemaining());

      postMatchBar.setVisible(view.isFinished()); // Fase 4.5-4: Voltar/Revanche só ao terminar

      if (view.isFinished()) {
        status.setText(outcomeText(view));
      } else if (view.isMyTurn()) {
        status.setText(
            mustPass
                ? "Sua vez — sem jogada, clique em Passar"
                : "Sua vez — escolha uma peça e clique numa ponta da mesa");
      } else {
        status.setText(" "); // de quem é a vez já aparece no TurnBanner, acima da mesa
      }
    }

    /**
     * Fase 4-3 ({@code BoardView.setInteractive}): terminada, ou pausada esperando reconexão de
     * quem está na vez (ADR-0013) — hover/clique no tabuleiro não fazem mais sentido visual.
     */
    private boolean interactive(ClientGameView view) {
      if (view.phase() != ClientGameView.Phase.IN_PROGRESS || view.isFinished()) {
        return false;
      }
      return view.seatOf(view.currentSeat())
          .map(s -> s.status() != SeatConnectionStatus.DISCONNECTED)
          .orElse(true);
    }

    private void tryPlay(MouseEvent e) {
      ClientGameView view = controller == null ? null : controller.view();
      if (view == null || !view.isMyTurn() || selected == null) {
        return;
      }
      TileDto dto = DtoMapper.tile(selected);
      if (!view.canPlay(dto)) {
        status.setText("essa peça não encaixa");
        return;
      }
      Line line = DtoMapper.line(view.line());
      End end;
      if (line.isEmpty()) {
        end = End.LEFT; // abertura: pontas simétricas
      } else {
        Optional<End> clicked = board.endAt(e.getPoint());
        if (clicked.isEmpty() || !line.fits(selected, clicked.get())) {
          status.setText("clique na ponta onde a peça encaixa");
          return;
        }
        end = clicked.get();
      }
      try {
        controller.play(dto, DtoMapper.end(end));
        selected = null;
        board.setHoverCandidate(null);
      } catch (TransportException ex) {
        error("jogada recusada: " + ex.getMessage());
      }
    }

    private String outcomeText(ClientGameView view) {
      var o = view.outcome();
      if (o == null) {
        return "fim de jogo";
      }
      if (o.reason() == EndReasonDto.ABORTED) {
        return "Partida abortada — sem vencedor.";
      }
      if (o.draw()) {
        return "Empate! (" + reasonLabel(o.reason()) + ")";
      }
      if (o.winningTeam() != null) {
        String label = o.winningTeam() == TeamDto.ODD ? "Ímpar (1 e 3)" : "Par (2 e 4)";
        return "Fim: venceu a Dupla " + label + " (" + reasonLabel(o.reason()) + ")";
      }
      String who = o.winningSeats().stream().map(view::nameOf).collect(Collectors.joining(" e "));
      return "Fim: venceu " + who + " (" + reasonLabel(o.reason()) + ")";
    }
  }

  /**
   * Fase 4.5-5 (ADR-0025): selo de um assento adversário na mesa posicional — nome, peças na mão,
   * situação ({@code [fora]}/{@code [caiu]}) e destaque de turno. Substitui a linha por jogador que
   * o antigo {@code PlayersPanel} desenhava numa coluna separada.
   */
  private static final class PlayerBadge extends JPanel {
    private static final Color TURN_BG = new Color(0xFF, 0xF3, 0xCD);

    private final JLabel nameLabel = new JLabel(" ");
    private final JLabel infoLabel = new JLabel(" ");
    private final Color defaultBg;

    PlayerBadge() {
      setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
      setOpaque(true);
      defaultBg = getBackground();
      nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
      infoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
      infoLabel.setFont(infoLabel.getFont().deriveFont(Font.PLAIN, 11f));
      add(nameLabel);
      add(infoLabel);
      setVisible(false);
    }

    void update(SeatView seat, boolean isTurn) {
      setVisible(true);
      Color seatColor = PlayerColors.of(Seat.of(seat.seat()));
      boolean eliminated = seat.status() == SeatConnectionStatus.ELIMINATED;
      boolean disconnected = seat.status() == SeatConnectionStatus.DISCONNECTED;

      setBackground(isTurn ? TURN_BG : defaultBg);
      setBorder(BorderFactory.createLineBorder(seatColor, isTurn ? 3 : 1));

      nameLabel.setText((isTurn ? "▶ " : "") + seat.name());
      nameLabel.setForeground(seatColor);
      nameLabel.setFont(nameLabel.getFont().deriveFont(isTurn ? Font.BOLD : Font.PLAIN));

      infoLabel.setText(
          seat.handCount()
              + (seat.handCount() == 1 ? " peça" : " peças")
              + (eliminated ? " [fora]" : "")
              + (disconnected ? " [caiu]" : ""));
    }
  }

  private static String reasonLabel(EndReasonDto reason) {
    return switch (reason) {
      case DOMINO -> "bateu";
      case BLOCKED -> "travou";
      case LAST_PLAYER_STANDING -> "sobrou sozinho";
      case ABORTED -> "abortada";
    };
  }

  private static String modeLabel(GameModeDto mode) {
    return mode == GameModeDto.PARTNERSHIP ? "duplas" : "um contra todos";
  }

  private static String phaseLabel(String phase) {
    return switch (phase) {
      case "LOBBY" -> "aguardando";
      case "IN_PROGRESS" -> "em jogo";
      case "FINISHED" -> "encerrada";
      default -> phase.toLowerCase();
    };
  }
}
