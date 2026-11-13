package main

import (
	"context"
	"encoding/binary"
	"fmt"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/df-mc/dragonfly/server"
	"github.com/df-mc/dragonfly/server/block/cube"
	"github.com/df-mc/dragonfly/server/world"
	"github.com/df-mc/dragonfly/server/world/chunk"
	"github.com/samuelffer/moboserver/server/internal/tunnel"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
)

// raknetMagic is the constant used by the RakNet handshake of Minecraft
// Bedrock. It is required for an unconnected ping.
var raknetMagic = []byte{0x00, 0xff, 0xff, 0x00, 0xfe, 0xfe, 0xfe, 0xfe, 0xfd, 0xfd, 0xfd, 0xfd, 0x12, 0x34, 0x56, 0x78}

// runSelftest starts a throw-away server in a temporary folder, verifies that
// the world generator produces solid terrain, pings the RakNet listener from
// the outside and shuts everything down again. It is executed by CI on every
// build, which catches generator and protocol regressions before an APK is
// published.
func runSelftest(opts options, log *slog.Logger) int {
	dir, err := os.MkdirTemp("", "mobosrv-selftest")
	if err != nil {
		fmt.Fprintln(os.Stderr, "selftest: temp dir:", err)
		return 1
	}
	defer os.RemoveAll(dir)

	opts.World = filepath.Join(dir, "world")
	opts.PlayersDir = filepath.Join(dir, "players")
	opts.DataDir = dir
	opts.BansFile = filepath.Join(dir, "bans.json")
	opts.Name = "MoboSelfTest"
	opts.MaxPlayers = 4
	opts.Gen = "terrain"
	// Xbox Live verification would require the Minecraft discovery service,
	// which is not reachable from CI runners. Authentication is orthogonal to
	// everything this test covers.
	opts.Auth = false
	opts.ignoreStdin = true

	if code := checkGenerator(); code != 0 {
		return code
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	ready := make(chan *server.Server, 1)
	opts.readyHook = func(srv *server.Server) { ready <- srv }

	done := make(chan error, 1)
	go func() { done <- runServer(ctx, opts, log, cancel) }()

	var srv *server.Server
	select {
	case srv = <-ready:
	case err := <-done:
		fmt.Fprintln(os.Stderr, "selftest: server did not start:", err)
		return 1
	case <-time.After(90 * time.Second):
		fmt.Fprintln(os.Stderr, "selftest: timeout waiting for the server to start")
		return 1
	}

	// Force one chunk through the real server path (this uses the generator the
	// same way a joining player would).
	block, err := world.Call(context.Background(), srv.World(), func(tx *world.Tx) (world.Block, error) {
		return tx.Block(cube.Pos{0, int(surfaceHeight(0, 0)), 0}), nil
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, "selftest: load chunk:", err)
		cancel()
		<-done
		return 1
	}
	if block == nil {
		fmt.Fprintln(os.Stderr, "selftest: chunk generation returned air at the surface")
		cancel()
		<-done
		return 1
	}
	fmt.Fprintf(os.Stderr, "selftest: surface block at spawn: %T\n", block)

	motd, err := pingServer(fmt.Sprintf("127.0.0.1:%d", opts.Port), 15*time.Second)
	if err != nil {
		fmt.Fprintln(os.Stderr, "selftest: raknet ping failed:", err)
		cancel()
		<-done
		return 1
	}
	fmt.Fprintf(os.Stderr, "selftest: pong: %s\n", motd)
	if err := validatePong(motd, opts.Port, opts.Name); err != nil {
		fmt.Fprintln(os.Stderr, "selftest: pong invalido:", err)
		cancel()
		<-done
		return 1
	}

	// The loopback ping above only proves the socket exists. This one proves the
	// server answers on a real interface address, which is exactly what breaks
	// when the listen address or the socket binding is wrong.
	iceNet := newICENetwork(opts.IfaceFile)
	if targets := localIPv4s(iceNet); len(targets) > 0 {
		target := targets[0]
		address := fmt.Sprintf("%s:%d", target.IP, opts.Port)
		lanMotd, err := pingServer(address, 8*time.Second)
		if err != nil {
			fmt.Fprintf(os.Stderr, "selftest: ping via %s (%s) failed: %v\n", address, target.Iface, err)
			cancel()
			<-done
			return 1
		}
		if err := validatePong(lanMotd, opts.Port, opts.Name); err != nil {
			fmt.Fprintf(os.Stderr, "selftest: pong invalido via %s: %v\n", address, err)
			cancel()
			<-done
			return 1
		}
		fmt.Fprintf(os.Stderr, "selftest: interface %s (%s) respondeu no ping\n", target.Iface, target.IP)
	}

	// Transporte por endereco: o cliente Bedrock sonda /v1/join antes de cair no
	// RakNet. O selftest exige que a sinalizacao esteja no ar (HTTP 200 + status
	// JSON com nome e protocolo), e nao apenas que o listener UDP tenha subido.
	if opts.NetherNet {
		signalling, err := waitForSignaling(opts.Port, 8*time.Second)
		if err != nil {
			fmt.Fprintln(os.Stderr, "selftest: sinalizacao NetherNet:", err)
			cancel()
			<-done
			return 1
		}
		fmt.Fprintf(os.Stderr, "selftest: sinalizacao NetherNet ok em TCP %d (GET /v1/join 200, nome %q, protocolo %d)\n",
			opts.Port, signalling.Name, signalling.Protocol)

		// E o passo que falhava no aparelho: criar o agente ICE e coletar
		// candidatos. Responder HTTP nao prova nada sobre a negociacao.
		minPort, maxPort, err := parsePortRange(opts.NetherNetPorts)
		if err != nil {
			fmt.Fprintln(os.Stderr, "selftest: faixa de portas do NetherNet:", err)
			cancel()
			<-done
			return 1
		}
		rep := checkICEGathering(ctx, iceNet, minPort, maxPort, 20*time.Second)
		if !rep.OK {
			fmt.Fprintln(os.Stderr, "selftest: ICE:", rep.Error)
			cancel()
			<-done
			return 1
		}
		fmt.Fprintf(os.Stderr, "selftest: ICE ok (%d candidatos via %s, host em %s)\n",
			rep.Candidates, rep.Source, describeCandidateHosts(rep.Hosts))
	}

	if opts.SelftestTunnel {
		if err := checkTunnelDiscovery(ctx, opts.Port, opts.Name); err != nil {
			fmt.Fprintln(os.Stderr, "selftest: tunel:", err)
			cancel()
			<-done
			return 1
		}
	}

	cancel()
	if err := <-done; err != nil {
		fmt.Fprintln(os.Stderr, "selftest: shutdown:", err)
		return 1
	}
	fmt.Fprintln(os.Stderr, "SELFTEST OK")
	return 0
}

// checkGenerator generates a chunk directly and validates the result, without
// any networking involved.
func checkGenerator() int {
	br := world.DefaultBlockRegistry
	br.Finalize()
	c := chunk.New(br, world.Overworld.Range())
	gen := newTerrain()
	gen.GenerateChunk(world.ChunkPos{0, 0}, c)

	h := clampHeight(surfaceHeight(0, 0), int16(c.Range().Min()), int16(c.Range().Max()))
	surface := c.Block(0, h, 0, 0)
	if surface == 0 {
		fmt.Fprintln(os.Stderr, "selftest: generator produced air at the surface")
		return 1
	}
	if bottom := c.Block(0, int16(c.Range().Min()), 0, 0); bottom == 0 {
		fmt.Fprintln(os.Stderr, "selftest: generator produced air at the bottom of the world")
		return 1
	}
	return 0
}

// pingServer performs a RakNet unconnected ping and returns the MOTD string of
// the server ("MCPE;name;protocol;version;players;max;...").
func pingServer(addr string, timeout time.Duration) (string, error) {
	conn, err := net.DialTimeout("udp", addr, timeout)
	if err != nil {
		return "", err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))

	pkt := make([]byte, 0, 33)
	pkt = append(pkt, 0x01)
	var ts [8]byte
	binary.BigEndian.PutUint64(ts[:], uint64(time.Now().UnixNano()/int64(time.Millisecond)))
	pkt = append(pkt, ts[:]...)
	pkt = append(pkt, raknetMagic...)
	var guid [8]byte
	binary.BigEndian.PutUint64(guid[:], 0x1122334455667788)
	pkt = append(pkt, guid[:]...)

	if _, err := conn.Write(pkt); err != nil {
		return "", err
	}
	buf := make([]byte, 4096)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			return "", err
		}
		if n < 35 || buf[0] != 0x1c {
			continue
		}
		length := int(binary.BigEndian.Uint16(buf[33:35]))
		if 35+length > n {
			length = n - 35
		}
		if length <= 0 {
			return "", fmt.Errorf("empty pong")
		}
		return string(buf[35 : 35+length]), nil
	}
}

// validatePong checks the payload of an Unconnected Pong, which is exactly what
// a Minecraft Bedrock client reads when it discovers the server on the local
// network. Checking the fields (and not only the "MCPE;" prefix) is what makes
// this a regression test for LAN discovery:
//
//	MCPE;<name>;<protocol>;<version>;<players>;<max>;<id>;<sub name>;<mode>;1;<port>;<port>;0;0;
func validatePong(motd string, port int, name string) error {
	parts := strings.Split(motd, ";")
	if len(parts) < 12 {
		return fmt.Errorf("payload com %d campos, esperado ao menos 12: %q", len(parts), motd)
	}
	if parts[0] != "MCPE" {
		return fmt.Errorf("edicao inesperada %q (esperado MCPE)", parts[0])
	}
	if parts[1] != name {
		return fmt.Errorf("nome %q difere do configurado %q", parts[1], name)
	}
	if got, err := strconv.Atoi(parts[2]); err != nil {
		return fmt.Errorf("protocolo invalido %q", parts[2])
	} else if got != protocol.CurrentProtocol {
		return fmt.Errorf("protocolo %d difere do compilado %d", got, protocol.CurrentProtocol)
	}
	if parts[3] != protocol.CurrentVersion {
		return fmt.Errorf("versao %q difere da compilada %q", parts[3], protocol.CurrentVersion)
	}
	if got, err := strconv.Atoi(parts[10]); err != nil {
		return fmt.Errorf("porta invalida %q", parts[10])
	} else if got != port {
		return fmt.Errorf("porta anunciada %d difere da real %d", got, port)
	}
	return nil
}

// checkTunnelDiscovery proves the whole external path in one shot: a relay is
// started locally, the tunnel client connects to it exactly like the phone
// does, and a RakNet discovery ping is sent to the *relay's* public UDP port.
// If a pong comes back, datagrams reached the game server through the tunnel.
func checkTunnelDiscovery(ctx context.Context, port int, name string) error {
	relayCtx, relayCancel := context.WithCancel(ctx)
	defer relayCancel()

	relay := &tunnel.Relay{Listen: "127.0.0.1:0", Tokens: []string{"selftest"}}
	ready := make(chan string, 1)
	go func() { _ = relay.ListenAndServe(relayCtx, func(addr string) { ready <- addr }) }()

	var control string
	select {
	case control = <-ready:
	case <-time.After(5 * time.Second):
		return fmt.Errorf("relay nao abriu o listener de controle")
	}

	client := &tunnel.Client{RelayAddr: control, Token: "selftest", LocalPort: port}
	go client.Run(relayCtx)

	var publicPort int
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if p := client.PublicPort(); p != 0 {
			publicPort = p
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if publicPort == 0 {
		return fmt.Errorf("o tunel nao estabeleceu sessao com o relay")
	}

	pong, err := pingServer(fmt.Sprintf("127.0.0.1:%d", publicPort), 10*time.Second)
	if err != nil {
		return fmt.Errorf("ping pelo tunel (porta publica %d): %w", publicPort, err)
	}
	if err := validatePong(pong, port, name); err != nil {
		return fmt.Errorf("pong invalido pelo tunel: %w", err)
	}
	fmt.Fprintf(os.Stderr, "selftest: tunel ok, porta publica %d respondeu ao ping RakNet\n", publicPort)
	return nil
}
