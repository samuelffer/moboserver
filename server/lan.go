package main

import (
	"net"
	"strings"
)

// How LAN discovery really works for Minecraft Bedrock
// ----------------------------------------------------
// Java Edition uses a multicast announcement to 224.0.2.60:4445 with a
// "[MOTD]..." payload. Bedrock does not: the *client* broadcasts a RakNet
// "Unconnected Ping" (0x01) to UDP 19132 (and to the addresses it learned from
// the interface), and every server that receives it answers with an
// "Unconnected Pong" (0x1c) whose payload is
//
//	MCPE;<motd>;<protocol>;<version>;<players>;<max>;<server id>;<motd 2>;<game mode>;<port v4>;<port v6>
//
// go-raknet (through gophertunnel, used by Dragonfly) answers those pings from
// the very same UDP socket the game listens on, as long as that socket is bound
// to a wildcard address. The previous build also ran a Java-style announcer to
// 224.0.2.60:4445 with a Bedrock payload; that combination is ignored by both
// editions, so it was removed instead of being "fixed". No second socket is
// opened: a second socket on the same port would either fail to bind or steal
// packets from the game listener.

// scoreInterface ranks network interfaces so the app can show the address a
// player on the same network should use.
func scoreInterface(name string) int {
	switch {
	case strings.HasPrefix(name, "wlan"), strings.HasPrefix(name, "ap"),
		strings.HasPrefix(name, "swlan"), strings.HasPrefix(name, "softap"):
		return 3
	case strings.HasPrefix(name, "eth"), strings.HasPrefix(name, "rndis"), strings.HasPrefix(name, "usb"):
		return 2
	case strings.HasPrefix(name, "rmnet"), strings.HasPrefix(name, "ccmni"),
		strings.HasPrefix(name, "radio"), strings.HasPrefix(name, "wwan"):
		return 0
	case strings.HasPrefix(name, "tun"), strings.HasPrefix(name, "tap"),
		strings.HasPrefix(name, "ppp"), strings.HasPrefix(name, "ipsec"):
		// VPN interfaces: traffic here is not the local network.
		return -1
	default:
		return 1
	}
}

// isPrivateIPv4 reports whether the address is in a range that cannot be
// reached from the internet directly (RFC 1918 + CGNAT shared space).
func isPrivateIPv4(ip string) bool {
	parsed := net.ParseIP(ip).To4()
	if parsed == nil {
		return false
	}
	return parsed.IsPrivate() || isSharedAddress(parsed)
}

// isSharedAddress reports whether ip is in 100.64.0.0/10, the shared address
// space used by carriers that apply CGNAT.
func isSharedAddress(ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	return ip4[0] == 100 && ip4[1] >= 64 && ip4[1] <= 127
}
