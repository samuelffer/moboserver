package main

// Adaptadores entre o gophertunnel (minecraft.Listener) e o Dragonfly
// (server.Listener).
//
// O Dragonfly tem esses adaptadores, mas eles sao privados, e o RakNet dele e
// criado por dentro de UserConfig a partir de UserConfig.Network.Transport. Como
// o MoboServer precisa montar o listener NetherNet com o seu proprio
// webrtc.SettingEngine (ver nethernet.go), os dois listeners sao criados aqui e
// entregues ao Dragonfly por Config.Listeners.

import (
	"fmt"
	"log/slog"

	"github.com/df-mc/dragonfly/server"
	"github.com/df-mc/dragonfly/server/session"
	"github.com/sandertv/gophertunnel/minecraft"
)

// moboListener adapts *minecraft.Listener to server.Listener, optionally
// closing a sidecar (the NetherNet signaling HTTP server) together with it.
type moboListener struct {
	*minecraft.Listener
	closeExtra func() error
}

func (l moboListener) Accept() (session.Conn, error) {
	conn, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	sessionConn, ok := conn.(session.Conn)
	if !ok {
		_ = conn.Close()
		return nil, fmt.Errorf("conexao aceita nao implementa session.Conn (%T)", conn)
	}
	return sessionConn, nil
}

func (l moboListener) Disconnect(conn session.Conn, reason string) error {
	mcConn, ok := conn.(*minecraft.Conn)
	if !ok {
		return fmt.Errorf("conexao %T nao e *minecraft.Conn", conn)
	}
	return l.Listener.Disconnect(mcConn, reason)
}

func (l moboListener) Close() error {
	err := l.Listener.Close()
	if l.closeExtra != nil {
		if extraErr := l.closeExtra(); err == nil {
			err = extraErr
		}
	}
	return err
}

// gopherListenConfig mirrors Dragonfly's own listener config, so the listeners
// created here accept players exactly like the ones it would create.
func gopherListenConfig(conf server.Config, log *slog.Logger) minecraft.ListenConfig {
	cfg := minecraft.ListenConfig{
		MaximumPlayers:         conf.MaxPlayers,
		StatusProvider:         conf.StatusProvider,
		AuthenticationDisabled: conf.AuthDisabled,
		ResourcePacks:          conf.Resources,
		TexturePacksRequired:   conf.ResourcesRequired,
		Compression:            conf.Compression,
	}
	if conf.Allower != nil {
		cfg.Allow = conf.Allower.Allow
	}
	if log != nil {
		cfg.ErrorLog = log.With("net origin", "gophertunnel")
	}
	return cfg
}

// startRakNet creates the RakNet listener (discovery and game over UDP) through
// the registered "raknet" network, which supplies the logger the Dragonfly
// would normally provide.
func startRakNet(conf server.Config, address string, log *slog.Logger) (server.Listener, error) {
	l, err := gopherListenConfig(conf, log).Listen("raknet", address)
	if err != nil {
		return nil, fmt.Errorf("create RakNet listener: %w", err)
	}
	log.Info("RakNet listener running.", "addr", l.Addr())
	return moboListener{Listener: l}, nil
}
