package main

// Android nao permite que aplicativos enumerem interfaces pelo netlink: o
// dominio SELinux de app comum nega sockets NETLINK_ROUTE desde o Android 8, e
// net.Interfaces() falha com
//
//	route ip+net: netlinkrib: permission denied
//
// O agente ICE do Pion chama net.Interfaces() ao ser criado, entao qualquer
// conexao NetherNet morria em
//
//	create peer connection: nethernet: obtain local ICE parameters:
//	failed to create network: route ip+net: netlinkrib: permission denied
//
// A saida sem root e nao depender do netlink: o app Android enumera as
// interfaces com as APIs publicas (java.net.NetworkInterface) e grava a lista em
// um arquivo JSON; o processo Go le esse arquivo e entrega a lista ao Pion como
// uma implementacao de transport.Net (pion/transport/v5), injetada no
// webrtc.SettingEngine com SetNet. Assim a criacao do agente, a coleta de
// candidatos e o monitoramento de mudanca de rede usam a lista do Android em vez
// do netlink.

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"sort"
	"sync"
	"time"

	"github.com/pion/transport/v5"
)

// ifaceAddressJSON is one address of an interface as reported by Android.
type ifaceAddressJSON struct {
	IP     string `json:"ip"`
	Prefix int    `json:"prefix"`
}

// ifaceJSON is one interface as reported by Android.
type ifaceJSON struct {
	Name     string             `json:"name"`
	Index    int                `json:"index"`
	MTU      int                `json:"mtu"`
	Up       bool               `json:"up"`
	Loopback bool               `json:"loopback"`
	Addrs    []ifaceAddressJSON `json:"addrs"`
}

// ifaceFile is the document the Android side writes.
type ifaceFile struct {
	Generated  string      `json:"generated"`
	Source     string      `json:"source"`
	Interfaces []ifaceJSON `json:"interfaces"`
}

// iceNetwork implements transport.Net for Pion. Only Interfaces,
// InterfaceByName and InterfaceByIndex differ from a pass-through to the
// standard library: they use the list provided by Android, which does not
// depend on netlink.
type iceNetwork struct {
	// path is the JSON file written by the Android app. Empty means "use the
	// standard library", which is what the self-test and the CI build do.
	path string

	mu     sync.Mutex
	source string // "android" or "netlink"
	count  int
}

func newICENetwork(path string) *iceNetwork {
	return &iceNetwork{path: path}
}

// Source reports where the last interface enumeration came from, so the app can
// show whether the Android list is really in use.
func (n *iceNetwork) Source() (string, int) {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.source, n.count
}

// ifacesFromAndroid parses the file written by the app.
func (n *iceNetwork) ifacesFromAndroid() ([]*transport.Interface, error) {
	b, err := os.ReadFile(n.path)
	if err != nil {
		return nil, fmt.Errorf("ler lista de interfaces do Android (%s): %w", n.path, err)
	}
	var doc ifaceFile
	if err := json.Unmarshal(b, &doc); err != nil {
		return nil, fmt.Errorf("lista de interfaces do Android invalida (%s): %w", n.path, err)
	}
	if len(doc.Interfaces) == 0 {
		return nil, fmt.Errorf("lista de interfaces do Android (%s) esta vazia", n.path)
	}
	out := make([]*transport.Interface, 0, len(doc.Interfaces))
	for _, fi := range doc.Interfaces {
		var flags net.Flag
		if fi.Up {
			flags |= net.FlagUp
		}
		if fi.Loopback {
			flags |= net.FlagLoopback
		}
		ni := net.Interface{Name: fi.Name, Index: fi.Index, MTU: fi.MTU, Flags: flags}
		ti := transport.NewInterface(ni)
		for _, a := range fi.Addrs {
			ip, ipnet, err := net.ParseCIDR(fmt.Sprintf("%s/%d", a.IP, a.Prefix))
			if err != nil {
				// One malformed address must not throw away the whole list.
				continue
			}
			ti.AddAddress(&net.IPNet{IP: ip, Mask: ipnet.Mask})
		}
		out = append(out, ti)
	}
	return out, nil
}

// ifacesFromNetlink uses the standard library, which only works where netlink is
// allowed (desktop, CI, some ROMs).
func ifacesFromNetlink() ([]*transport.Interface, error) {
	list, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	out := make([]*transport.Interface, 0, len(list))
	for _, ni := range list {
		ti := transport.NewInterface(ni)
		if addrs, err := ni.Addrs(); err == nil {
			for _, a := range addrs {
				ti.AddAddress(a)
			}
		}
		out = append(out, ti)
	}
	return out, nil
}

func (n *iceNetwork) Interfaces() ([]*transport.Interface, error) {
	if n.path != "" {
		list, err := n.ifacesFromAndroid()
		if err != nil {
			// Do not silently fall back to netlink: on Android it is blocked, and
			// pretending otherwise hides the real reason ICE failed.
			n.record("android", 0)
			return nil, err
		}
		n.record("android", len(list))
		return list, nil
	}
	list, err := ifacesFromNetlink()
	if err != nil {
		return nil, err
	}
	n.record("netlink", len(list))
	return list, nil
}

func (n *iceNetwork) record(source string, count int) {
	n.mu.Lock()
	n.source, n.count = source, count
	n.mu.Unlock()
}

func (n *iceNetwork) InterfaceByName(name string) (*transport.Interface, error) {
	list, err := n.Interfaces()
	if err != nil {
		return nil, err
	}
	for _, iface := range list {
		if iface.Name == name {
			return iface, nil
		}
	}
	return nil, fmt.Errorf("%w: %s", transport.ErrInterfaceNotFound, name)
}

func (n *iceNetwork) InterfaceByIndex(index int) (*transport.Interface, error) {
	list, err := n.Interfaces()
	if err != nil {
		return nil, err
	}
	for _, iface := range list {
		if iface.Index == index {
			return iface, nil
		}
	}
	return nil, fmt.Errorf("%w: index %d", transport.ErrInterfaceNotFound, index)
}

func (n *iceNetwork) ListenPacket(network, address string) (net.PacketConn, error) {
	return net.ListenPacket(network, address)
}

func (n *iceNetwork) ListenUDP(network string, laddr *net.UDPAddr) (transport.UDPConn, error) {
	return net.ListenUDP(network, laddr)
}

func (n *iceNetwork) ListenTCP(network string, laddr *net.TCPAddr) (transport.TCPListener, error) {
	return net.ListenTCP(network, laddr)
}

func (n *iceNetwork) Dial(network, address string) (net.Conn, error) {
	return net.Dial(network, address)
}

func (n *iceNetwork) DialUDP(network string, laddr, raddr *net.UDPAddr) (transport.UDPConn, error) {
	return net.DialUDP(network, laddr, raddr)
}

func (n *iceNetwork) DialTCP(network string, laddr, raddr *net.TCPAddr) (transport.TCPConn, error) {
	return net.DialTCP(network, laddr, raddr)
}

func (n *iceNetwork) ResolveIPAddr(network, address string) (*net.IPAddr, error) {
	return net.ResolveIPAddr(network, address)
}

func (n *iceNetwork) ResolveUDPAddr(network, address string) (*net.UDPAddr, error) {
	return net.ResolveUDPAddr(network, address)
}

func (n *iceNetwork) ResolveTCPAddr(network, address string) (*net.TCPAddr, error) {
	return net.ResolveTCPAddr(network, address)
}

func (n *iceNetwork) CreateDialer(dialer *net.Dialer) transport.Dialer { return dialer }

func (n *iceNetwork) CreateListenConfig(lc *net.ListenConfig) transport.ListenConfig { return lc }

// ifaceSummary renders the list of interfaces the ICE agent will use. The app
// shows it so it is obvious which addresses may appear in host candidates.
func ifaceSummary(network transport.Net) ([]string, error) {
	list, err := network.Interfaces()
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(list))
	for _, iface := range list {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipnet, ok := a.(*net.IPNet); ok && ipnet.IP.IsLoopback() {
				continue
			}
			out = append(out, fmt.Sprintf("%s/%s", iface.Name, a.String()))
		}
	}
	sort.Strings(out)
	return out, nil
}

// writeIfaceFile renders the JSON the Android app writes. It is used by the
// self-test and by tests that simulate the Android interface list.
func writeIfaceFile(path string, ifaces []ifaceJSON) error {
	doc := ifaceFile{
		Generated:  time.Now().UTC().Format(time.RFC3339),
		Source:     "android",
		Interfaces: ifaces,
	}
	b, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

var errNoInterfaces = errors.New("nenhuma interface de rede disponivel")
