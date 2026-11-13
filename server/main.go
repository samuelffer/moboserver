package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/df-mc/dragonfly/server"
	"github.com/df-mc/dragonfly/server/block"
	"github.com/df-mc/dragonfly/server/world"
	"github.com/df-mc/dragonfly/server/world/biome"
	"github.com/df-mc/dragonfly/server/world/generator"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
)

// appVersion is the version of the embedded server, kept in sync with the
// Android app version by the build workflow.
const appVersion = "1.3.0"

// options holds every command line flag the Android app passes to the server
// process. Everything is explicit: the process never reads a config file, so
// the app is always the single source of truth.
type options struct {
	World        string
	PlayersDir   string
	DataDir      string
	BansFile     string
	Name         string
	Port         int
	MaxPlayers   int
	Auth         bool
	GameMode     string
	Difficulty   string
	ViewDistance int
	Gen          string
	DNS          string
	Tunnel       string
	TunnelToken  string
	TunnelTLS    bool
	AllowOpen      bool
	NetherNet      bool
	NetherNetPorts string
	IfaceFile      string
	Selftest     bool
	SelftestTunnel bool
	ShowVersion  bool

	// readyHook and ignoreStdin are only used by the self test, they are not
	// command line flags.
	readyHook   func(srv *server.Server)
	ignoreStdin bool
}

func parseFlags() options {
	var o options
	flag.StringVar(&o.World, "world", "world", "folder that holds the world (Bedrock LevelDB format)")
	flag.StringVar(&o.PlayersDir, "players", "players", "folder that holds player data")
	flag.StringVar(&o.DataDir, "data", ".", "base data folder")
	flag.StringVar(&o.BansFile, "bans", "", "path of the ban list file")
	flag.StringVar(&o.Name, "name", "MoboServer", "name shown in the Minecraft server list")
	flag.IntVar(&o.Port, "port", 19132, "UDP port to listen on")
	flag.IntVar(&o.MaxPlayers, "max", 4, "maximum amount of players")
	flag.BoolVar(&o.Auth, "auth", true, "require Xbox Live authentication")
	flag.StringVar(&o.GameMode, "gamemode", "survival", "default game mode: survival, creative, adventure or spectator")
	flag.StringVar(&o.Difficulty, "difficulty", "normal", "difficulty: peaceful, easy, normal or hard")
	flag.IntVar(&o.ViewDistance, "view", 8, "maximum chunk radius (view distance) for players")
	flag.StringVar(&o.Gen, "gen", "terrain", "world generator for new chunks: terrain or flat")
	flag.StringVar(&o.DNS, "dns", "", "DNS servers for this network, comma separated (Android has no resolv.conf)")
	flag.BoolVar(&o.AllowOpen, "allow-open", false,
		"start without Xbox authentication when the login service is unreachable")
	flag.BoolVar(&o.NetherNet, "nethernet", true,
		"also listen on the NetherNet transport (clients joining by address probe it first)")
	flag.StringVar(&o.NetherNetPorts, "nethernet-ports", "19133-19140",
		"UDP ports used by NetherNet player connections")
	flag.StringVar(&o.IfaceFile, "ifaces", "",
		"JSON file with the interface list written by the Android app "+
			"(netlink is blocked for apps on Android, so ICE cannot enumerate interfaces itself)")
	flag.StringVar(&o.Tunnel, "tunnel", "", "relay host:port of the reverse UDP tunnel (empty disables it)")
	flag.StringVar(&o.TunnelToken, "tunnel-token", "", "token accepted by the tunnel relay")
	flag.BoolVar(&o.TunnelTLS, "tunnel-tls", true, "use TLS for the tunnel control connection")
	flag.BoolVar(&o.SelftestTunnel, "selftest-tunnel", false, "in selftest, also cover the UDP tunnel end to end")
	flag.BoolVar(&o.Selftest, "selftest", false, "start a throw-away server, ping it and exit")
	flag.BoolVar(&o.ShowVersion, "version", false, "print version information and exit")
	flag.Parse()
	return o
}

func main() {
	opts := parseFlags()
	if opts.ShowVersion {
		fmt.Printf("mobosrv %s dragonfly %s bedrock %s protocol %d\n", appVersion, dragonflyVersion(), protocol.CurrentVersion, protocol.CurrentProtocol)
		return
	}

	logger := slog.New(eventHandler{})
	slog.SetDefault(logger)

	if opts.Selftest {
		os.Exit(runSelftest(opts, logger))
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigs := make(chan os.Signal, 2)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		cancel()
	}()

	if err := runServer(ctx, opts, logger, cancel); err != nil {
		emitLog("error", err.Error())
		emit(map[string]any{"t": "fatal", "msg": friendlyError(err)})
		os.Exit(1)
	}
}

// dragonflyVersion is the Dragonfly release the binary was built against. It
// is displayed in the app's "about" screen.
func dragonflyVersion() string { return "0.11.6" }

// runServer configures, starts and supervises the Minecraft server until ctx is
// cancelled. It returns only after the world has been saved and closed.
func runServer(ctx context.Context, opts options, log *slog.Logger, stop context.CancelFunc) error {
	setupResolver(strings.Split(opts.DNS, ","))
	setupTrustStore()

	for _, dir := range []string{opts.DataDir, opts.PlayersDir, opts.World} {
		if dir == "" {
			continue
		}
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return fmt.Errorf("não foi possível criar a pasta %s: %w", dir, err)
		}
	}

	imported := worldExists(opts.World)
	if imported {
		emitLog("info", "Mundo encontrado: abrindo dados existentes...")
	} else {
		emitLog("info", "Nenhum mundo encontrado: um novo mundo sera gerado.")
		if opts.Gen == "flat" {
			emitLog("info", "Gerador selecionado: superflat")
		} else {
			emitLog("info", "Gerador selecionado: terreno natural")
		}
	}

	if err := checkPortAvailable(opts.Port); err != nil {
		return err
	}

	bans, err := loadBanList(opts.BansFile)
	if err != nil {
		emitLog("warn", "Lista de banidos não pôde ser carregada: "+err.Error())
	}

	uc := server.DefaultConfig()
	uc.Network.Address = fmt.Sprintf("0.0.0.0:%d", opts.Port)
	// RakNet (UDP) e o transporte historico. Clientes atuais que entram por
	// endereco sondam antes o endpoint NetherNet: HTTPS em /v1/join na porta do
	// jogo (e em 443/80 quando a porta e 19132) e so depois caem para o RakNet.
	// Se ninguem responde, a sondagem demora e o cliente fica em "localizando
	// servidor"; por isso o NetherNet tambem e servido, na mesma porta do jogo.
	// RakNet (UDP) e o transporte historico. O NetherNet e montado em
	// server/nethernet.go (Config.Listeners) e nao pelo Dragonfly: aquele caminho
	// cria o webrtc.SettingEngine internamente e nao permite entregar a lista de
	// interfaces do Android ao Pion, que no aparelho falha com
	// "netlinkrib: permission denied" ao criar o agente ICE.
	uc.Network.Transport = []string{"raknet"}
	uc.Server.Name = opts.Name
	uc.Server.AuthEnabled = opts.Auth
	uc.World.SaveData = true
	uc.World.Folder = opts.World
	uc.Players.MaxCount = opts.MaxPlayers
	uc.Players.MaximumChunkRadius = opts.ViewDistance
	uc.Players.SaveData = true
	uc.Players.Folder = opts.PlayersDir
	uc.Resources.AutoBuildPack = false
	uc.Resources.Required = false
	uc.Resources.Folder = filepath.Join(opts.DataDir, "resources")

	conf, err := uc.Config(log)
	if err != nil {
		return fmt.Errorf("não foi possível abrir o mundo (arquivo incompatível ou corrompido): %w", err)
	}
	conf.AuthDisabled = !opts.Auth
	if opts.Auth {
		if err := xboxAuthReachable(ctx, protocol.CurrentVersion); err != nil {
			if !opts.AllowOpen {
				return fmt.Errorf("não foi possível validar o login do Xbox Live: %w. "+
					"Confira a internet do celular; para jogar sem verificação de conta, ligue "+
					"\"Modo aberto\" em Ajustes (não é o padrão)", err)
			}
			emitLog("warn", "Login do Xbox Live indisponível ("+err.Error()+"): seguindo em MODO ABERTO, "+
				"como configurado em Ajustes.")
			conf.AuthDisabled = true
		} else {
			emitLog("info", "Login do Xbox Live acessível: contas da Microsoft serão verificadas.")
		}
	}
	conf.Allower = &banAllower{bans: bans}
	conf.Generator = generatorFor(opts.Gen)
	conf.SaveInterval = 2 * time.Minute
	conf.ChunkUnloadInterval = 2 * time.Minute
	conf.DisableResourceBuilding = true

	// Transportes. RakNet cobre descoberta e jogo em UDP; NetherNet serve a
	// sinalizacao HTTP na mesma porta (GET /v1/join) e a midia WebRTC nos portos
	// UDP configurados. Os dois usam a mesma implementacao de rede para ICE.
	iceNet := newICENetwork(opts.IfaceFile)
	address := fmt.Sprintf("0.0.0.0:%d", opts.Port)
	var udpMin, udpMax uint16
	if opts.NetherNet {
		var perr error
		udpMin, udpMax, perr = parsePortRange(opts.NetherNetPorts)
		if perr != nil {
			return fmt.Errorf("faixa de portas do NetherNet invalida: %w", perr)
		}
	}
	conf.Listeners = []func(server.Config) (server.Listener, error){
		func(c server.Config) (server.Listener, error) {
			return startRakNet(c, address, log)
		},
	}
	if opts.NetherNet {
		conf.Listeners = append(conf.Listeners, func(c server.Config) (server.Listener, error) {
			return startNetherNet(c, netherNetOptions{
				Address:        address,
				KeyFile:        filepath.Join(opts.DataDir, "keys", "server_identity_key.pem"),
				Domain:         "self",
				UDPPortsMin:    udpMin,
				UDPPortsMax:    udpMax,
				AllowAnonymous: c.AuthDisabled,
				Network:        iceNet,
				Log:            log,
			})
		})
	}

	srv := conf.New()
	srv.Listen()

	overworld := srv.World()
	if overworld != nil {
		if gm, ok := parseGameMode(opts.GameMode); ok {
			overworld.SetDefaultGameMode(gm)
		}
		if d, ok := parseDifficulty(opts.Difficulty); ok {
			overworld.SetDifficulty(d)
		}
		emitLog("info", fmt.Sprintf("Modo de jogo: %s | Dificuldade: %s", opts.GameMode, opts.Difficulty))

		// Brand new worlds get an explicit spawn so players never land inside
		// terrain (natural generation) or fall into the void (superflat).
		if !imported {
			overworld.SetSpawn(defaultSpawn(opts.Gen, world.Overworld))
			if n := srv.Nether(); n != nil {
				n.SetSpawn(defaultSpawn("flat", world.Nether))
			}
			if e := srv.End(); e != nil {
				e.SetSpawn(defaultSpawn("flat", world.End))
			}
			spawn := overworld.Spawn()
			emitLog("info", fmt.Sprintf("Ponto de nascimento: %d, %d, %d", spawn[0], spawn[1], spawn[2]))
		}
	}

	rt := &runtime{
		opts:   opts,
		srv:    srv,
		reg:    newRegistry(),
		bans:   bans,
		stop:   stop,
		log:    log,
		loaded: imported,
	}

	// Make sure the UDP port really answers before telling the app that the
	// server is online. If Dragonfly could not create its listener (usually
	// because the phone has no internet for the Xbox Live verification) this
	// fails with an actionable message instead of a silent dead server.
	// Cada transporte e verificado separadamente: abrir a porta, receber
	// sinalizacao, estabelecer conexao e concluir o login sao etapas distintas e
	// o console mostra qual delas falhou.
	raknetErr := waitForListener(opts.Port, 12*time.Second)
	if raknetErr == nil {
		emitLog("info", fmt.Sprintf("Listener RakNet ativo: UDP :%d (descoberta e jogo)", opts.Port))
	} else {
		emitLog("error", "Listener RakNet falhou: "+raknetErr.Error())
	}
	var (
		signalling signallingInfo
		signErr    error
		iceRep     iceReport
	)
	if opts.NetherNet {
		signalling, signErr = waitForSignaling(opts.Port, 12*time.Second)
		if signErr == nil {
			emitLog("info", fmt.Sprintf("Sinalizacao NetherNet ok: GET /v1/join respondeu 200 em TCP :%d "+
				"(nome %q, protocolo %d, versao %s)", opts.Port, signalling.Name, signalling.Protocol, signalling.Version))
			emitLog("info", fmt.Sprintf("Nome que o cliente recebe ao digitar o endereco: %q", signalling.Name))
		} else {
			emitLog("error", "Sinalizacao NetherNet falhou: "+signErr.Error())
		}

		// Responder HTTP nao e negociar: aqui o agente ICE e criado e os
		// candidatos sao coletados de verdade, que era o passo que falhava no
		// Android por causa do netlink bloqueado.
		iceRep = checkICEGathering(ctx, iceNet, udpMin, udpMax, 12*time.Second)
		if iceRep.OK {
			emitLog("info", fmt.Sprintf("ICE ok: %d candidato(s) UDP, host em %s (interfaces: %s)",
				iceRep.Candidates, describeCandidateHosts(iceRep.Hosts), strings.Join(iceRep.Interfaces, ", ")))
		} else {
			emitLog("error", "ICE falhou, a negociacao NetherNet nao vai completar: "+iceRep.Error)
		}
	}
	if raknetErr != nil && (signErr != nil || !opts.NetherNet) {
		_ = srv.Close()
		return fmt.Errorf("nenhum transporte abriu a porta: %v", raknetErr)
	}

	emit(map[string]any{
		"t":        "ready",
		"port":     opts.Port,
		"name":     opts.Name,
		"version":  protocol.CurrentVersion,
		"protocol": protocol.CurrentProtocol,
		"max":      opts.MaxPlayers,
		"gen":      opts.Gen,
		"imported": imported,
		"auth":      !conf.AuthDisabled,
		"nethernet": opts.NetherNet && signErr == nil,
		"raknet":    raknetErr == nil,
		"signaling": signalling,
		"ice":       iceRep,
	})
	emitLog("info", fmt.Sprintf("Servidor de Minecraft Bedrock %s (protocolo %d) escutando na porta UDP %d",
		protocol.CurrentVersion, protocol.CurrentProtocol, opts.Port))
	if opts.readyHook != nil {
		opts.readyHook(srv)
	}

	if opts.Tunnel != "" {
		startTunnel(ctx, opts)
	}

	// A descoberta na rede local nao precisa de nenhum anuncio extra: o proprio
	// listener RakNet responde aos "Unconnected Ping" que os clientes Bedrock
	// enviam em broadcast para a porta do jogo (ver server/lan.go).
	emitLog("info", fmt.Sprintf("Descoberta na rede local: o servidor responde aos pings de busca do Minecraft "+
		"na porta UDP %d (aba Servidores / LAN do jogo).", opts.Port))

	// Auto teste: o servidor pinga a si mesmo em cada IPv4 do aparelho. Isso
	// separa "o servidor nao esta escutando" de "a rede nao entrega o pacote",
	// que e a duvida numero 1 de quem nao consegue conectar.
	go func() {
		targets := probeInterfaces(opts.Port, localIPv4s(iceNet))
		for _, target := range targets {
			if target.OK {
				emitLog("info", fmt.Sprintf("Servidor responde em %s:%d (%s)", target.IP, opts.Port, target.Iface))
			} else {
				emitLog("warn", fmt.Sprintf("Servidor nao respondeu em %s:%d (%s) a partir do proprio aparelho. "+
					"Possiveis causas: VPN ativa, regra de firewall do sistema ou interface sem rota.",
					target.IP, opts.Port, target.Iface))
			}
		}
		emit(map[string]any{"t": "lan", "targets": targets})
	}()

	go rt.acceptLoop()
	go rt.commandLoop(ctx)
	go rt.ticker(ctx)

	<-ctx.Done()
	emitLog("info", "Desligando o servidor e salvando o mundo...")
	if err := srv.Close(); err != nil {
		emitLog("error", "Erro ao fechar o servidor: "+err.Error())
	}
	emitLog("info", "Mundo salvo. Servidor desligado.")
	emit(map[string]any{"t": "stopped"})
	return nil
}

// runtime bundles everything the control loops need.
type runtime struct {
	opts   options
	srv    *server.Server
	reg    *registry
	bans   *banList
	stop   context.CancelFunc
	log    *slog.Logger
	loaded bool
}

// acceptLoop registers every player that joins and removes it again when it
// leaves. It is the only place where player state is created.
func (rt *runtime) acceptLoop() {
	for p := range rt.srv.Accept() {
		name := p.Name()
		entry := rt.reg.add(p.UUID(), p.H(), playerInfo{
			Name:   name,
			XUID:   p.XUID(),
			Device: p.DeviceModel(),
			Addr:   addrString(p.Addr()),
			Since:  time.Now().Unix(),
		})
		p.Handle(&joinHandler{reg: rt.reg})
		emitLog("info", fmt.Sprintf("%s entrou no servidor (%d/%d)", name, rt.srv.PlayerCount(), rt.opts.MaxPlayers))
		emit(map[string]any{"t": "join", "name": entry.Name, "xuid": entry.XUID, "device": entry.Device})
		rt.emitPlayers()
	}
}

// ticker emits a periodic snapshot so the app's player list stays accurate even
// if an event is missed.
func (rt *runtime) ticker(ctx context.Context) {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			rt.emitPlayers()
		}
	}
}

func (rt *runtime) emitPlayers() {
	players := rt.reg.snapshot()
	emit(map[string]any{
		"t":     "players",
		"items": players,
		"count": len(players),
		"max":   rt.opts.MaxPlayers,
	})
}

// waitForListener pings the RakNet listener on the loopback interface until it
// answers, so "online" in the app always means "a Minecraft client can reach
// this port".
func waitForListener(port int, timeout time.Duration) error {
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	deadline := time.Now().Add(timeout)
	for {
		if _, err := pingServer(addr, 3*time.Second); err == nil {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("o listener RakNet não abriu a porta UDP %d: verifique se outra "+
				"pessoa não está usando essa porta no aparelho", port)
		}
		time.Sleep(400 * time.Millisecond)
	}
}

// worldExists reports whether dir holds a Bedrock world (level.dat present).
func worldExists(dir string) bool {
	if dir == "" {
		return false
	}
	st, err := os.Stat(filepath.Join(dir, "level.dat"))
	return err == nil && !st.IsDir()
}

// checkPortAvailable makes sure nothing else on the device is already using the
// UDP port, so the app can show a helpful message instead of a silent failure.
func checkPortAvailable(port int) error {
	if port <= 0 || port > 65535 {
		return fmt.Errorf("porta inválida: %d", port)
	}
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: port})
	if err != nil {
		return fmt.Errorf("a porta %d já está em uso neste celular. Escolha outra porta nas configurações", port)
	}
	return c.Close()
}

func generatorFor(kind string) func(world.Dimension) world.Generator {
	return func(dim world.Dimension) world.Generator {
		switch dim {
		case world.Overworld:
			if strings.EqualFold(kind, "flat") {
				return generator.NewFlat(biome.Plains{}, []world.Block{block.Grass{}, block.Dirt{}, block.Dirt{}, block.Bedrock{}})
			}
			return newTerrain()
		case world.Nether:
			return generator.NewFlat(biome.NetherWastes{}, []world.Block{block.Netherrack{}, block.Netherrack{}, block.Netherrack{}, block.Bedrock{}})
		default:
			return generator.NewFlat(biome.End{}, []world.Block{block.EndStone{}, block.EndStone{}, block.EndStone{}, block.Bedrock{}})
		}
	}
}

func parseGameMode(s string) (world.GameMode, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "creative", "criativo":
		return world.GameModeCreative, true
	case "adventure", "aventura":
		return world.GameModeAdventure, true
	case "spectator", "espectador":
		return world.GameModeSpectator, true
	case "survival", "sobrevivencia", "sobrevivência":
		return world.GameModeSurvival, true
	}
	return world.GameModeSurvival, false
}

func parseDifficulty(s string) (world.Difficulty, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "peaceful", "pacifico", "pacífico":
		return world.DifficultyPeaceful, true
	case "easy", "facil", "fácil":
		return world.DifficultyEasy, true
	case "hard", "dificil", "difícil":
		return world.DifficultyHard, true
	case "normal":
		return world.DifficultyNormal, true
	}
	return world.DifficultyNormal, false
}

func addrString(a net.Addr) string {
	if a == nil {
		return ""
	}
	return a.String()
}

// friendlyError turns low level errors into something a player can act on.
func friendlyError(err error) string {
	msg := err.Error()
	switch {
	case errors.Is(err, os.ErrPermission):
		return "O Android bloqueou o acesso a um arquivo do servidor: " + msg
	case strings.Contains(msg, "já está em uso"):
		return msg
	case strings.Contains(msg, "no space"):
		return "O celular ficou sem espaço em disco: " + msg
	}
	return msg
}
