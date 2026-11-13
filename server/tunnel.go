package main

import (
	"context"

	"github.com/samuelffer/moboserver/server/internal/tunnel"
)

// startTunnel connects to the public relay and forwards player datagrams to the
// local game server. It is entirely optional: when the app has no relay
// configured, LAN play works exactly the same.
func startTunnel(ctx context.Context, opts options) {
	var client *tunnel.Client
	client = &tunnel.Client{
		RelayAddr: opts.Tunnel,
		Token:     opts.TunnelToken,
		LocalPort: opts.Port,
		UseTLS:    opts.TunnelTLS,
		Log:       func(msg string) { emitLog("info", msg) },
		OnState: func(state, detail string) {
			address := ""
			if addrs := client.PublicAddrs(); len(addrs) > 0 {
				address = addrs[0]
			}
			emit(map[string]any{
				"t":       "tunnel",
				"state":   state,
				"detail":  detail,
				"address": address,
				"port":    client.PublicPort(),
			})
			switch state {
			case "connected":
				emitLog("info", "Acesso pela internet pelo túnel: "+address)
			case "reconnecting":
				emitLog("warn", "Túnel caiu, reconectando: "+detail)
			}
		},
	}
	go client.Run(ctx)
}
