// Command relay is the public side of the MoboServer tunnel: it accepts
// outbound sessions from phones and hands each one a public UDP port that
// forwards player traffic to the game server running on the phone.
//
// It has no dependency on Minecraft, keeps no state and stores no credentials
// beyond the shared token list, so it can run on the cheapest VPS or in a
// container. See relay/README.md for deployment.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/samuelffer/moboserver/server/internal/tunnel"
)

func main() {
	var (
		listen  = flag.String("listen", ":9000", "TCP address for control connections")
		tokens  = flag.String("tokens", "", "comma separated tokens accepted (required)")
		ports   = flag.String("ports", "", "public UDP port range, e.g. 19200-19300 (empty = ephemeral)")
		idle    = flag.Duration("idle", 60*time.Second, "close a player flow after this much silence")
		udpAddr = flag.String("udp-listen", "", "TCP address for UDP-range sessions (MOBOTUN/2, e.g. :9001; empty disables)")
		udpMax  = flag.Int("udp-ports", 8, "maximum public UDP ports per UDP-range session")
		verbose = flag.Bool("v", true, "log sessions")
	)
	flag.Parse()

	tokenList := splitTokens(*tokens)
	if len(tokenList) == 0 {
		fmt.Fprintln(os.Stderr, "relay: informe -tokens (ou use -tokens dev para testes locais)")
		os.Exit(2)
	}
	portRange, err := tunnel.ParsePortRange(*ports)
	if err != nil {
		fmt.Fprintln(os.Stderr, "relay:", err)
		os.Exit(2)
	}

	logf := func(msg string) {
		if *verbose {
			fmt.Printf("%s %s\n", time.Now().Format("15:04:05"), msg)
		}
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// One relay per token keeps the policy trivial: a session is accepted only
	// if its token is in the list.
	// Modo por faixa de portas (MOBOTUN/2): uma porta pública por conexão ICE,
	// usado pelo NetherNet. Roda em um listener TCP separado para que a política
	// de um modo não dependa do outro.
	if *udpAddr != "" {
		udpRelay := &tunnel.UDPRelay{
			Listen:    *udpAddr,
			Tokens:    tokenList,
			PortRange: portRange,
			MaxPorts:  *udpMax,
			Log:       logf,
		}
		go func() {
			if err := udpRelay.ListenAndServe(ctx, func(addr string) {
				logf("controle UDP em " + addr)
			}); err != nil {
				fmt.Fprintln(os.Stderr, "relay UDP:", err)
				stop()
			}
		}()
	}

	r := &tunnel.Relay{Listen: *listen, PortRange: portRange, FlowIdle: *idle, Log: logf, Tokens: tokenList}
	logf(fmt.Sprintf("aceitando %d token(s)", len(tokenList)))
	if err := r.ListenAndServe(ctx, func(addr string) {
		logf("controle em " + addr)
	}); err != nil {
		fmt.Fprintln(os.Stderr, "relay:", err)
		os.Exit(1)
	}
	logf("encerrado")
}

func splitTokens(value string) []string {
	var out []string
	for _, part := range strings.Split(value, ",") {
		part = strings.TrimSpace(part)
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}
