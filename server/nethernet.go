package main

// Listener NetherNet do MoboServer.
//
// O Dragonfly v0.11.6 cria o NetherNet internamente (server.NetherNetConfig),
// mas monta o webrtc.SettingEngine sozinho e nao expoe um jeito de trocar a
// fonte de interfaces. Como no Android o netlink e bloqueado (ver icenet.go), o
// unico ponto em que a API do WebRTC pode ser configurada e
// nethernet.ListenConfig.API — o Dragonfly o preenche internamente, e
// Config.Listeners permite registrar um listener proprio. Este arquivo faz
// exatamente o que o Dragonfly faz, com um SettingEngine que recebe SetNet.
//
// Cadeia confirmada nas versoes fixadas:
//
//	dragonfly v0.11.6  server.NetherNetConfig.Listener -> nethernet.ListenConfig{API: webrtc.NewAPI(...)}
//	go-nethernet v1.0.26  Listener.negotiate -> newConn(api, ...) -> api.NewICEGatherer(...)
//	pion/webrtc v4.2.21  SettingEngine.NewICEGatherer -> ice.NewAgentWithOptions(..., Net: e.Net)
//	pion/ice v4.4.4     agent.net == nil -> stdnet.NewNet() (netlink) | usa agent.net.Interfaces() quando definido
//
// Ou seja: SetNet e a unica costura publica, e ela chega na criacao efetiva da
// conexao, porque o Conn do go-nethernet usa o mesmo *webrtc.API.

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/df-mc/dragonfly/server"
	"github.com/df-mc/go-nethernet"
	"github.com/df-mc/go-nethernet/endpoint"
	"github.com/pion/ice/v4"
	"github.com/pion/transport/v5"
	"github.com/pion/webrtc/v4"
	"github.com/sandertv/gophertunnel/minecraft"
)

// netherNetOptions holds everything the custom NetherNet listener needs.
type netherNetOptions struct {
	Address     string
	KeyFile     string
	Domain      string
	UDPPortsMin uint16
	UDPPortsMax uint16
	// PublicIP, quando preenchido, faz o ICE anunciar candidatos com este
	// endereco (o da VPS com mapeamento 1:1 de portas) em vez do IP local.
	// Sem mapeamento 1:1 nao use: o candidato publicado precisa chegar ao
	// celular na mesma porta.
	PublicIP string
	// UDPMux, quando definido, faz o ICE usar o mux do relay (portas publicas)
	// em vez de soquetes locais.
	UDPMux         ice.UDPMux
	AllowAnonymous bool
	Network        transport.Net
	Log            *slog.Logger
}

// startNetherNet creates the NetherNet listener (HTTP signaling + WebRTC data
// channels over ICE) using the injected network implementation.
func startNetherNet(conf server.Config, opts netherNetOptions) (server.Listener, error) {
	log := opts.Log.With("net origin", "nethernet")
	key, err := loadOrCreateNetherNetKey(opts.KeyFile, log)
	if err != nil {
		return nil, err
	}
	domain := opts.Domain
	if domain == "" {
		domain = "self"
	}

	var settingEngine webrtc.SettingEngine
	// O mesmo SettingEngine usado para configurar ICE e o que entrega a lista de
	// interfaces do Android; sem isto o agente ICE chama stdnet.NewNet() e falha
	// com netlinkrib: permission denied no aparelho.
	settingEngine.SetNet(opts.Network)
	if opts.PublicIP != "" {
		// API disponivel no pion/webrtc v4.2.21 (go.sum). Reescreve apenas os
		// candidatos host; a porta anunciada e a porta real do socket, por isso
		// o mapeamento publico precisa manter os mesmos numeros.
		// Na versao fixada (v4.2.21) a funcao nao devolve erro.
		settingEngine.SetNAT1To1IPs([]string{opts.PublicIP}, webrtc.ICECandidateTypeHost)
		log.Info("ICE vai anunciar um IP publico.", "ip", opts.PublicIP)
	}
	var mux ice.UDPMux
	if opts.UDPMux != nil {
		// Portas publicas do relay: o ICE anuncia IP+porta alcancaveis de fora.
		mux = opts.UDPMux
		settingEngine.SetICEUDPMux(mux)
		log.Info("ICE usando o mux do relay.")
	} else if opts.UDPPortsMin != 0 && opts.UDPPortsMin == opts.UDPPortsMax {
		mux, err = ice.NewMultiUDPMuxFromPort(int(opts.UDPPortsMin))
		if err != nil {
			return nil, fmt.Errorf("allocate UDP mux: %w", err)
		}
		settingEngine.SetICEUDPMux(mux)
	} else if opts.UDPPortsMax != 0 {
		if err := settingEngine.SetEphemeralUDPPortRange(opts.UDPPortsMin, opts.UDPPortsMax); err != nil {
			return nil, fmt.Errorf("configure ephemeral udp port range: %w", err)
		}
	}

	listenConfig := nethernet.ListenConfig{
		API:            webrtc.NewAPI(webrtc.WithSettingEngine(settingEngine)),
		Log:            log,
		AllowAnonymous: opts.AllowAnonymous,
		IssueServerIdentity: func(ctx context.Context) (*nethernet.Identity, error) {
			return nethernet.ServerIdentity(key, domain)
		},
	}

	httpLog := opts.Log.With("net origin", "nethernet-http")
	handler := endpoint.HandlerConfig{Logger: httpLog}.New()

	tcp, err := net.Listen("tcp", opts.Address)
	if err != nil {
		if mux != nil {
			_ = mux.Close()
		}
		return nil, fmt.Errorf("listen NetherNet HTTP: %w", err)
	}

	// gophertunnel cria o listener a partir do minecraft.NetherNet, exatamente
	// como o NetherNetConfig.Listener do Dragonfly faria.
	inner, err := gopherListenConfig(conf, opts.Log).ListenNetwork(minecraft.NetherNet{
		Signaling:    handler,
		ListenConfig: listenConfig,
		Log:          log,
	}, handler.NetworkID())
	if err != nil {
		_ = tcp.Close()
		if mux != nil {
			_ = mux.Close()
		}
		return nil, fmt.Errorf("create NetherNet listener: %w", err)
	}

	httpServer := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	go func() {
		err := httpServer.Serve(tcp)
		if err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			log.Error("NetherNet HTTP listener closed unexpectedly: " + err.Error())
		}
	}()

	log.Info("NetherNet listener running.", "addr", tcp.Addr())
	return moboListener{
		Listener: inner,
		closeExtra: func() error {
			var firstErr error
			if err := httpServer.Close(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				firstErr = err
			}
			if mux != nil {
				if err := mux.Close(); err != nil && firstErr == nil {
					firstErr = err
				}
			}
			return firstErr
		},
	}, nil
}

// loadOrCreateNetherNetKey reads the P-384 identity key, generating and saving a
// new one when the file does not exist yet. The format matches Dragonfly's
// (SEC1 "EC PRIVATE KEY"), so an existing key keeps working.
func loadOrCreateNetherNetKey(path string, log *slog.Logger) (*ecdsa.PrivateKey, error) {
	if path == "" {
		key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
		if err != nil {
			return nil, fmt.Errorf("generate temporary NetherNet key: %w", err)
		}
		return key, nil
	}
	b, err := os.ReadFile(path)
	if err == nil {
		key, err := parseECPrivateKey(b)
		if err != nil {
			return nil, fmt.Errorf("import NetherNet key %s: %w", path, err)
		}
		return key, nil
	}
	if !os.IsNotExist(err) {
		return nil, fmt.Errorf("read NetherNet key %s: %w", path, err)
	}

	key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate NetherNet key: %w", err)
	}
	der, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, fmt.Errorf("encode NetherNet key: %w", err)
	}
	block := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der})
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create key folder: %w", err)
	}
	if err := os.WriteFile(path, block, 0o600); err != nil {
		return nil, fmt.Errorf("save NetherNet key: %w", err)
	}
	log.Info("Generated a private key for NetherNet listener.", "path", path)
	return key, nil
}

// parseECPrivateKey accepts both SEC1 ("EC PRIVATE KEY") and PKCS#8
// ("PRIVATE KEY") PEM blocks.
func parseECPrivateKey(data []byte) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("invalid PEM block")
	}
	if key, err := x509.ParseECPrivateKey(block.Bytes); err == nil {
		return key, nil
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse private key: %w", err)
	}
	key, ok := parsed.(*ecdsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("private key is %T, not ECDSA", parsed)
	}
	return key, nil
}
