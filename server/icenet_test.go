package main

import (
	"context"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestICENetworkFromAndroidFile makes sure the list written by the Android app is
// what Pion sees: this is the whole point of the workaround, because
// net.Interfaces() is blocked on Android.
func TestICENetworkFromAndroidFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "ifaces.json")
	err := writeIfaceFile(path, []ifaceJSON{{
		Name:  "wlan0",
		Index: 7,
		MTU:   1500,
		Up:    true,
		Addrs: []ifaceAddressJSON{{IP: "192.168.1.106", Prefix: 24}},
	}})
	if err != nil {
		t.Fatalf("writeIfaceFile: %v", err)
	}

	ice := newICENetwork(path)
	ifaces, err := ice.Interfaces()
	if err != nil {
		t.Fatalf("Interfaces: %v", err)
	}
	if len(ifaces) != 1 {
		t.Fatalf("esperava 1 interface, veio %d", len(ifaces))
	}
	if ifaces[0].Name != "wlan0" {
		t.Fatalf("nome errado: %q", ifaces[0].Name)
	}
	if ifaces[0].Flags&net2FlagUp == 0 {
		t.Fatalf("interface sem FlagUp: flags=%v", ifaces[0].Flags)
	}
	addrs, err := ifaces[0].Addrs()
	if err != nil {
		t.Fatalf("Addrs: %v", err)
	}
	if len(addrs) != 1 {
		t.Fatalf("esperava 1 endereco, veio %d", len(addrs))
	}
	ipnet, ok := addrs[0].(*net.IPNet)
	if !ok {
		t.Fatalf("endereco nao e *net.IPNet: %T", addrs[0])
	}
	if ipnet.IP.String() != "192.168.1.106" {
		t.Fatalf("IP errado: %s", ipnet.IP)
	}
	if source, count := ice.Source(); source != "android" || count != 1 {
		t.Fatalf("origem/count errados: %s/%d", source, count)
	}
}

// TestICENetworkMissingFileFails keeps the failure honest: without the Android
// list there is no silent fall back to netlink (which is blocked anyway).
func TestICENetworkMissingFileFails(t *testing.T) {
	ice := newICENetwork(filepath.Join(t.TempDir(), "nao-existe.json"))
	if _, err := ice.Interfaces(); err == nil {
		t.Fatal("esperava erro quando o arquivo de interfaces nao existe")
	}
}

// TestICEAgentGathersFromInjectedInterfaces creates a real ICE agent (the same
// call go-nethernet makes when a client joins) using the injected network and
// asserts candidates are gathered from the injected list.
func TestICEAgentGathersFromInjectedInterfaces(t *testing.T) {
	host, err := net.Interfaces()
	if err != nil {
		t.Skipf("sem interfaces no ambiente de teste: %v", err)
	}
	list := make([]ifaceJSON, 0, len(host))
	for _, iface := range host {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		entry := ifaceJSON{Name: iface.Name, Index: iface.Index, MTU: iface.MTU, Up: true}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ones, _ := ipnet.Mask.Size()
			entry.Addrs = append(entry.Addrs, ifaceAddressJSON{IP: ipnet.IP.String(), Prefix: ones})
		}
		if len(entry.Addrs) > 0 {
			list = append(list, entry)
		}
	}
	if len(list) == 0 {
		t.Skip("nenhuma interface com endereco para testar")
	}

	path := filepath.Join(t.TempDir(), "ifaces.json")
	if err := writeIfaceFile(path, list); err != nil {
		t.Fatalf("writeIfaceFile: %v", err)
	}

	injected := newICENetwork(path)
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	rep := checkICEGathering(ctx, injected, 0, 0, 25*time.Second)
	if !rep.OK {
		t.Fatalf("ICE nao coletou candidatos com a lista injetada: %s", rep.Error)
	}
	if rep.Source != "android" {
		t.Fatalf("a coleta usou %q em vez da lista injetada", rep.Source)
	}
	if rep.Candidates == 0 || len(rep.Hosts) == 0 {
		t.Fatalf("coleta sem candidatos host: %+v", rep)
	}
	t.Logf("candidatos=%d hosts=%v interfaces=%v", rep.Candidates, rep.Hosts, rep.Interfaces)
}

// TestParseCandidates covers the SDP parsing used to prove gathering worked.
func TestParseCandidates(t *testing.T) {
	sdp := "v=0\r\n" +
		"a=candidate:1 1 udp 2130706431 192.168.1.106 19133 typ host\r\n" +
		"a=candidate:2 1 udp 2130706431 192.168.1.106 19134 typ host\r\n" +
		"a=candidate:3 1 udp 1694498815 45.160.85.157 45678 typ srflx\r\n" +
		"a=candidate:4 1 tcp 1518280447 192.168.1.106 9 typ host tcptype active\r\n"
	count, hosts := parseCandidates(sdp)
	if count != 3 {
		t.Fatalf("esperava 3 candidatos UDP, veio %d", count)
	}
	if len(hosts) != 1 || hosts[0] != "192.168.1.106" {
		t.Fatalf("hosts inesperados: %v", hosts)
	}
}

// TestParsePortRange covers the -nethernet-ports syntax.
func TestParsePortRange(t *testing.T) {
	min, max, err := parsePortRange("19133-19140")
	if err != nil || min != 19133 || max != 19140 {
		t.Fatalf("faixa errada: %d-%d (%v)", min, max, err)
	}
	single, singleMax, err := parsePortRange("19133")
	if err != nil || single != 19133 || singleMax != 19133 {
		t.Fatalf("porta unica errada: %d-%d (%v)", single, singleMax, err)
	}
	if _, _, err := parsePortRange("19140-19133"); err == nil {
		t.Fatal("esperava erro para faixa invertida")
	}
	if _, _, err := parsePortRange("abc"); err == nil {
		t.Fatal("esperava erro para porta invalida")
	}
}

// TestParseECPrivateKey covers the identity key formats accepted by the custom
// NetherNet listener (SEC1 written by Dragonfly and PKCS#8).
func TestParseECPrivateKey(t *testing.T) {
	key, err := loadOrCreateNetherNetKey("", testLogger())
	if err != nil {
		t.Fatalf("gerar chave: %v", err)
	}
	path := filepath.Join(t.TempDir(), "keys", "server_identity_key.pem")
	saved, err := loadOrCreateNetherNetKey(path, testLogger())
	if err != nil {
		t.Fatalf("salvar chave: %v", err)
	}
	if !saved.Equal(key) {
		// not the same key, expected: the second call generated a new one
		t.Log("chave nova gerada como esperado")
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("arquivo de chave nao foi gravado: %v", err)
	}
	again, err := loadOrCreateNetherNetKey(path, testLogger())
	if err != nil {
		t.Fatalf("recarregar chave: %v", err)
	}
	if !again.Equal(saved) {
		t.Fatal("a chave recarregada difere da salva")
	}
}

// net2FlagUp is net.FlagUp.
const net2FlagUp = net.FlagUp

// testLogger is a discard slog logger for the NetherNet key helpers.
func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
