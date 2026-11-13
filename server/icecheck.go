package main

// Verificacao da negociacao NetherNet.
//
// O endpoint HTTP de sinalizacao responder 200 em /v1/join prova apenas que o
// servidor tem um handler HTTP no ar. Ela NAO prova que um cliente consegue
// negociar: a negociacao comeca depois, quando o cliente manda o SDP e o
// servidor cria o agente ICE. Era exatamente essa etapa que falhava no Android
// (netlink bloqueado). checkICEGathering cria o mesmo agente e coleta candidatos
// de verdade, usando a mesma implementacao de rede do listener, para que o app
// possa diferenciar as duas coisas.

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"

	"github.com/pion/transport/v5"
	"github.com/pion/webrtc/v4"
)

// iceReport is what the app receives about the ICE self-check.
type iceReport struct {
	OK         bool     `json:"ok"`
	Error      string   `json:"error,omitempty"`
	Candidates int      `json:"candidates"`
	Hosts      []string `json:"hosts,omitempty"`
	Interfaces []string `json:"interfaces,omitempty"`
	Source     string   `json:"source"`
	ElapsedMS  int64    `json:"elapsedMs"`
}

// checkICEGathering creates a peer connection with the injected network
// implementation and waits for ICE gathering to finish. This is the same code
// path a real NetherNet connection takes (api.NewICEGatherer ->
// ice.NewAgentWithOptions -> localInterfaces -> net.Interfaces()).
func checkICEGathering(ctx context.Context, network transport.Net, minPort, maxPort uint16, timeout time.Duration) iceReport {
	start := time.Now()
	rep := iceReport{}
	defer func() { rep.ElapsedMS = time.Since(start).Milliseconds() }()

	if summary, err := ifaceSummary(network); err != nil {
		rep.Error = "enumerar interfaces: " + err.Error()
		return rep
	} else {
		rep.Interfaces = summary
	}
	// Report where the interface list came from: "android" means the injected
	// list was really used instead of netlink.
	if provider, ok := network.(interface{ Source() (string, int) }); ok {
		if source, _ := provider.Source(); source != "" {
			rep.Source = source
		}
	}

	var settingEngine webrtc.SettingEngine
	settingEngine.SetNet(network)
	settingEngine.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4, webrtc.NetworkTypeUDP6})
	if maxPort != 0 && maxPort >= minPort {
		if err := settingEngine.SetEphemeralUDPPortRange(minPort, maxPort); err != nil {
			rep.Error = "configurar faixa de portas UDP: " + err.Error()
			return rep
		}
	}

	api := webrtc.NewAPI(webrtc.WithSettingEngine(settingEngine))
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		rep.Error = "criar PeerConnection: " + err.Error()
		return rep
	}
	defer func() { _ = pc.Close() }()

	if _, err := pc.CreateDataChannel("moboserver-ice-check", nil); err != nil {
		rep.Error = "criar DataChannel: " + err.Error()
		return rep
	}
	gathered := webrtc.GatheringCompletePromise(pc)
	offer, err := pc.CreateOffer(nil)
	if err != nil {
		rep.Error = "criar oferta: " + err.Error()
		return rep
	}
	if err := pc.SetLocalDescription(offer); err != nil {
		rep.Error = "definir descricao local: " + err.Error()
		return rep
	}

	select {
	case <-gathered:
	case <-time.After(timeout):
		rep.Error = fmt.Sprintf("timeout de %s ao coletar candidatos ICE", timeout)
		return rep
	case <-ctx.Done():
		rep.Error = "cancelado antes de coletar candidatos: " + ctx.Err().Error()
		return rep
	}

	desc := pc.LocalDescription()
	if desc == nil {
		rep.Error = "descricao local vazia apos a coleta"
		return rep
	}
	count, hosts := parseCandidates(desc.SDP)
	rep.Candidates, rep.Hosts = count, hosts
	if count == 0 {
		rep.Error = "a coleta de ICE terminou sem nenhum candidato (nenhuma interface utilizavel)"
		return rep
	}
	rep.OK = true
	return rep
}

// parseCandidates extracts the number of UDP candidates and the host candidate
// addresses from an SDP. Host candidates are the ones that make LAN play work.
func parseCandidates(sdp string) (int, []string) {
	count := 0
	var hosts []string
	for _, line := range strings.Split(sdp, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "a=candidate:") {
			continue
		}
		tokens := strings.Fields(line)
		// a=candidate:<foundation> <component> <protocol> <priority> <ip> <port> typ <type>
		if len(tokens) < 8 || tokens[2] != "udp" {
			continue
		}
		count++
		if tokens[7] == "host" {
			hosts = appendUnique(hosts, tokens[4])
		}
	}
	return count, hosts
}

func appendUnique(list []string, v string) []string {
	for _, item := range list {
		if item == v {
			return list
		}
	}
	return append(list, v)
}

// parsePortRange parses "port" or "min-max" into a UDP port range.
func parsePortRange(s string) (uint16, uint16, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, 0, nil
	}
	if !strings.Contains(s, "-") {
		v, err := strconv.ParseUint(s, 10, 16)
		if err != nil {
			return 0, 0, fmt.Errorf("porta invalida %q: %w", s, err)
		}
		return uint16(v), uint16(v), nil
	}
	parts := strings.SplitN(s, "-", 2)
	min, err := strconv.ParseUint(strings.TrimSpace(parts[0]), 10, 16)
	if err != nil {
		return 0, 0, fmt.Errorf("porta inicial invalida em %q: %w", s, err)
	}
	max, err := strconv.ParseUint(strings.TrimSpace(parts[1]), 10, 16)
	if err != nil {
		return 0, 0, fmt.Errorf("porta final invalida em %q: %w", s, err)
	}
	if min > max {
		return 0, 0, fmt.Errorf("faixa de portas invertida %q", s)
	}
	return uint16(min), uint16(max), nil
}

// describeCandidateHosts renders host candidate addresses for the app.
func describeCandidateHosts(hosts []string) string {
	if len(hosts) == 0 {
		return "nenhum endereco local"
	}
	parsed := make([]net.IP, 0, len(hosts))
	for _, h := range hosts {
		if ip := net.ParseIP(h); ip != nil {
			parsed = append(parsed, ip)
		}
	}
	if len(parsed) == 0 {
		return strings.Join(hosts, ", ")
	}
	out := make([]string, 0, len(hosts))
	for _, ip := range parsed {
		out = append(out, ip.String())
	}
	return strings.Join(out, ", ")
}
