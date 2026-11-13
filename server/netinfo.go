package main

import (
	"net"
	"strconv"
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
