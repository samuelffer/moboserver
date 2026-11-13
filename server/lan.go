package main

import (
	"context"
	"fmt"
	"net"
	"strings"
	"syscall"
	"time"

	"github.com/df-mc/dragonfly/server"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
)

// lanBroadcastAddr is where Bedrock clients listen for worlds opened to LAN
// (the same address every Bedrock host uses).
const lanBroadcastAddr = "224.0.2.60:4445"

// lanServerID identifies the announcement; clients only need it to be stable.
const lanServerID uint64 = 13253860892328930865

// startLanAnnouncer advertises this server the same way a Bedrock world does
// when it is opened to LAN. Phones, PCs and consoles that are on the same Wi-Fi
// then list "MoboServer" under LAN/Worlds without anyone typing an address.
func startLanAnnouncer(ctx context.Context, srv *server.Server, opts options) {
	ip := lanInterfaceIPv4()
	if ip == nil {
		emitLog("warn", "Sem rede local (Wi-Fi): o servidor não será anunciado na aba LAN.")
		return
	}

	conn, err := net.ListenPacket("udp4", "0.0.0.0:0")
	if err != nil {
		emitLog("warn", "Não foi possível anunciar na LAN: "+err.Error())
		return
	}
	if udp, ok := conn.(*net.UDPConn); ok {
		// Without this the packet may leave through the mobile data interface.
		if raw, err := udp.SyscallConn(); err == nil {
			_ = raw.Control(func(fd uintptr) {
				_ = syscall.SetsockoptInet4Addr(int(fd), syscall.IPPROTO_IP, syscall.IP_MULTICAST_IF, *ip)
			})
		}
	}

	target, err := net.ResolveUDPAddr("udp4", lanBroadcastAddr)
	if err != nil {
		_ = conn.Close()
		return
	}

	emitLog("info", fmt.Sprintf("Anunciando na rede local: %s:%d (aba LAN do Minecraft)",
		net.IP(ip[:]).String(), opts.Port))

	go func() {
		defer conn.Close()
		ticker := time.NewTicker(1500 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				_, _ = conn.WriteTo([]byte(lanPayload(srv, opts)), target)
			}
		}
	}()
}

// lanPayload builds the announcement exactly like a LAN world does.
func lanPayload(srv *server.Server, opts options) string {
	motd := sanitiseMotd(opts.Name)
	return fmt.Sprintf("[MCPE;%s;%d;%s;%d;%d;%d;%s;%s;1;%d;%d]",
		motd,
		protocol.CurrentProtocol,
		protocol.CurrentVersion,
		srv.PlayerCount(),
		opts.MaxPlayers,
		lanServerID,
		motd,
		gameModeLabel(opts.GameMode),
		opts.Port,
		opts.Port,
	)
}

func sanitiseMotd(name string) string {
	clean := strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(name, ";", ","), "\n", " "))
	if clean == "" {
		return "MoboServer"
	}
	if len(clean) > 60 {
		clean = clean[:60]
	}
	return clean
}

func gameModeLabel(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "creative":
		return "Creative"
	case "adventure":
		return "Adventure"
	case "spectator":
		return "Spectator"
	default:
		return "Survival"
	}
}

// lanInterfaceIPv4 returns the IPv4 of the interface used by the local network,
// preferring Wi-Fi/hotspot interfaces over cellular ones.
func lanInterfaceIPv4() *[4]byte {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var best *[4]byte
	bestScore := -1
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		score := scoreInterface(iface.Name)
		if score < 0 {
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
			if ip4 == nil || !ip4.IsPrivate() {
				continue
			}
			if score > bestScore {
				best = &[4]byte{ip4[0], ip4[1], ip4[2], ip4[3]}
				bestScore = score
			}
		}
	}
	return best
}

func scoreInterface(name string) int {
	switch {
	case strings.HasPrefix(name, "wlan"):
		return 3
	case strings.HasPrefix(name, "ap"), strings.HasPrefix(name, "swlan"):
		return 2
	case strings.HasPrefix(name, "eth"):
		return 1
	case strings.HasPrefix(name, "rmnet"), strings.HasPrefix(name, "ccmni"):
		return -1
	default:
		return 0
	}
}
