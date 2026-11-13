package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/pion/transport/v5"
)

// lanTarget describes one local address the server may be reachable on. The app
// shows this list so it is obvious which IP a friend must type, and whether the
// server really answers on the Wi-Fi interface (not only on loopback).
type lanTarget struct {
	Iface string `json:"iface"`
	IP    string `json:"ip"`
	OK    bool   `json:"ok"`
}

// localIPv4s lists every non-loopback, non-link-local IPv4 of the device,
// preferring Wi-Fi/hotspot interfaces first so the UI shows the useful one.
// It reads the interfaces through the same transport.Net the ICE agent uses:
// on Android net.Interfaces() is blocked, so asking the standard library here
// would silently report "no addresses" while the server is perfectly reachable.
func localIPv4s(network transport.Net) []lanTarget {
	preferred := make([]lanTarget, 0, 4)
	others := make([]lanTarget, 0, 4)
	ifaces, err := network.Interfaces()
	if err != nil {
		return nil
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipNet, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipNet.IP.To4()
			if ip4 == nil || ip4.IsLoopback() || ip4.IsLinkLocalUnicast() || ip4.IsUnspecified() {
				continue
			}
			entry := lanTarget{Iface: iface.Name, IP: ip4.String()}
			if scoreInterface(iface.Name) >= 2 {
				preferred = append(preferred, entry)
			} else {
				others = append(others, entry)
			}
		}
	}
	return append(preferred, others...)
}

// probeInterfaces pings the server on each local address. A failing address is
// not fatal: it only means something between the socket and that interface is
// filtering traffic (for example a VPN or a ROM-level firewall).
func probeInterfaces(port int, targets []lanTarget) []lanTarget {
	for i := range targets {
		address := net.JoinHostPort(targets[i].IP, strconv.Itoa(port))
		if _, err := pingServer(address, 1500*time.Millisecond); err == nil {
			targets[i].OK = true
		}
	}
	return targets
}

// signallingInfo is what GET /v1/join answered. Status 200 alone only proves
// that something speaks HTTP on the port, so the status document (name,
// protocol, version) is parsed as well: it is the same JSON the Bedrock client
// receives when it probes the server by address.
type signallingInfo struct {
	Status   int    `json:"status"`
	Name     string `json:"name,omitempty"`
	Protocol int    `json:"protocol,omitempty"`
	Version  string `json:"version,omitempty"`
	Players  int    `json:"players,omitempty"`
	Max      int    `json:"max,omitempty"`
	Body     string `json:"body,omitempty"`
}

// waitForSignaling verifies the NetherNet signaling endpoint on the game port.
// Clients that join by address ask for /v1/join there (HTTPS first, plaintext
// HTTP on the same port) before falling back to RakNet. The check requires
// status 200, a JSON content type and a status document with the server name and
// protocol: an endpoint that answers with an empty body, an error page or a
// redirect is not usable and used to pass this check silently.
func waitForSignaling(port int, timeout time.Duration) (signallingInfo, error) {
	url := fmt.Sprintf("http://127.0.0.1:%d/v1/join", port)
	client := &http.Client{
		Timeout: 4 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	deadline := time.Now().Add(timeout)
	info := signallingInfo{}
	var lastErr error = fmt.Errorf("porta %d nao aceitou conexao", port)
	for time.Now().Before(deadline) {
		resp, err := client.Get(url)
		if err != nil {
			lastErr = err
			time.Sleep(300 * time.Millisecond)
			continue
		}
		body, readErr := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		_ = resp.Body.Close()
		info.Status = resp.StatusCode
		info.Body = strings.TrimSpace(string(body))
		if readErr != nil {
			lastErr = fmt.Errorf("ler resposta de /v1/join: %w", readErr)
			time.Sleep(300 * time.Millisecond)
			continue
		}
		if resp.StatusCode != http.StatusOK {
			lastErr = fmt.Errorf("/v1/join respondeu HTTP %d: %q", resp.StatusCode, info.Body)
			time.Sleep(300 * time.Millisecond)
			continue
		}
		if ct := resp.Header.Get("Content-Type"); !strings.Contains(ct, "application/json") {
			lastErr = fmt.Errorf("/v1/join respondeu 200 sem JSON (Content-Type %q)", ct)
			time.Sleep(300 * time.Millisecond)
			continue
		}
		var status struct {
			Name           string `json:"name"`
			Protocol       int    `json:"protocol"`
			Version        string `json:"version"`
			Players        int    `json:"players"`
			MaxPlayerCount int    `json:"maxPlayers"`
		}
		if len(body) == 0 {
			// The handler answers 200 with an empty body until the game listener
			// has published its pong data; keep waiting instead of accepting it.
			lastErr = fmt.Errorf("/v1/join respondeu 200 com corpo vazio (status do servidor ainda nao publicado)")
			time.Sleep(300 * time.Millisecond)
			continue
		}
		if err := json.Unmarshal(body, &status); err != nil {
			lastErr = fmt.Errorf("/v1/join respondeu 200 com corpo que nao e JSON: %q", info.Body)
			time.Sleep(300 * time.Millisecond)
			continue
		}
		if status.Name == "" || status.Protocol == 0 {
			lastErr = fmt.Errorf("/v1/join respondeu 200 sem nome/protocolo do servidor: %q", info.Body)
			time.Sleep(300 * time.Millisecond)
			continue
		}
		info.Name, info.Protocol, info.Version = status.Name, status.Protocol, status.Version
		info.Players, info.Max = status.Players, status.MaxPlayerCount
		return info, nil
	}
	return info, fmt.Errorf("sinalizacao NetherNet em %s nao ok: %w", url, lastErr)
}
