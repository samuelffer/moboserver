package main

import (
	"bufio"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"
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
func localIPv4s() []lanTarget {
	preferred := make([]lanTarget, 0, 4)
	others := make([]lanTarget, 0, 4)
	ifaces, err := net.Interfaces()
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

// waitForSignaling verifies that the NetherNet HTTP signaling endpoint answers on
// the game port. Clients that join by address ask for /v1/join there (HTTPS
// first, plaintext HTTP on the same port) before falling back to RakNet, so an
// endpoint that silently drops packets is what makes the client sit on
// "localizando servidor" for a long time.
func waitForSignaling(port int, timeout time.Duration) error {
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	deadline := time.Now().Add(timeout)
	var lastErr error = fmt.Errorf("porta nao aceitou conexao")
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", addr, 1500*time.Millisecond)
		if err != nil {
			lastErr = err
			time.Sleep(300 * time.Millisecond)
			continue
		}
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		request := "GET /v1/join HTTP/1.1\r\nHost: " + addr + "\r\nUser-Agent: MoboServer\r\nConnection: close\r\n\r\n"
		_, _ = conn.Write([]byte(request))
		reader := bufio.NewReader(conn)
		line, readErr := reader.ReadString('\n')
		_ = conn.Close()
		if readErr == nil && strings.HasPrefix(line, "HTTP/") {
			return nil
		}
		lastErr = fmt.Errorf("resposta inesperada em /v1/join: %q", strings.TrimSpace(line))
		time.Sleep(300 * time.Millisecond)
	}
	return fmt.Errorf("sem sinalizacao em TCP %s: %w", addr, lastErr)
}
