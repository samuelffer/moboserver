package tunnel

import (
	"context"
	"net"
	"testing"
	"time"
)

// O relay por faixa de portas tem que preservar, nas duas direções, qual porta
// pública pertence a qual conexão e qual endereço de jogador falou com ela.
func TestUDPRelayPreservaPortaEOrigem(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	relay := &UDPRelay{Listen: "127.0.0.1:0", MaxPorts: 4}
	ready := make(chan string, 1)
	go func() { _ = relay.ListenAndServe(ctx, func(control string) { ready <- control }) }()
	control := <-ready

	client := &UDPClient{RelayAddr: control, Token: "teste", Ports: 2}
	assigned := make(chan []int, 1)
	client.OnReady = func(ports []int) { assigned <- ports }
	go client.Run(ctx)

	var ports []int
	select {
	case ports = <-assigned:
	case <-ctx.Done():
		t.Fatal("o relay não atribuiu portas")
	}
	if len(ports) != 2 {
		t.Fatalf("esperava 2 portas, recebi %v", ports)
	}

	// Dois "jogadores" diferentes, um em cada porta pública.
	playerA, err := net.Dial("udp", addrWithPort(control, ports[0]))
	if err != nil {
		t.Fatal(err)
	}
	defer playerA.Close()
	playerB, err := net.Dial("udp", addrWithPort(control, ports[1]))
	if err != nil {
		t.Fatal(err)
	}
	defer playerB.Close()

	if _, err := playerA.Write([]byte("primeiro")); err != nil {
		t.Fatal(err)
	}
	if _, err := playerB.Write([]byte("segundo")); err != nil {
		t.Fatal(err)
	}

	for _, tc := range []struct {
		index   int
		want    string
		payload string
	}{{0, "primeiro", "resposta-0"}, {1, "segundo", "resposta-1"}} {
		readCtx, cancelRead := context.WithTimeout(ctx, 10*time.Second)
		from, payload, err := client.Read(readCtx, tc.index)
		cancelRead()
		if err != nil {
			t.Fatalf("porta %d: %v", tc.index, err)
		}
		if string(payload) != tc.want {
			t.Fatalf("porta %d: esperava %q, recebi %q", tc.index, tc.want, payload)
		}
		if err := client.Write(tc.index, from, []byte(tc.payload)); err != nil {
			t.Fatalf("porta %d: %v", tc.index, err)
		}
	}

	for _, tc := range []struct {
		conn    net.Conn
		payload string
	}{{playerA, "resposta-0"}, {playerB, "resposta-1"}} {
		_ = tc.conn.(*net.UDPConn).SetReadDeadline(time.Now().Add(10 * time.Second))
		buf := make([]byte, 512)
		n, err := tc.conn.Read(buf)
		if err != nil {
			t.Fatalf("resposta não voltou: %v", err)
		}
		if string(buf[:n]) != tc.payload {
			t.Fatalf("esperava %q, recebi %q", tc.payload, buf[:n])
		}
	}
}

// Um token errado não pode reservar portas.
func TestUDPRelayRejeitaTokenInvalido(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	relay := &UDPRelay{Listen: "127.0.0.1:0", Tokens: []string{"certo"}, MaxPorts: 2}
	ready := make(chan string, 1)
	go func() { _ = relay.ListenAndServe(ctx, func(control string) { ready <- control }) }()
	control := <-ready

	client := &UDPClient{RelayAddr: control, Token: "errado", Ports: 1}
	failed := make(chan struct{}, 1)
	client.Log = func(string) { failed <- struct{}{} }
	done := make(chan struct{})
	go func() { client.Run(ctx); close(done) }()

	select {
	case <-failed:
	case <-ctx.Done():
		t.Fatal("o relay aceitou um token inválido")
	}
	if ports := client.AssignedPorts(); len(ports) != 0 {
		t.Fatalf("portas atribuídas com token inválido: %v", ports)
	}
	cancel()
	<-done
}

// addrWithPort troca a porta de um host:porta de controle mantendo o host.
func addrWithPort(control string, port int) string {
	host, _, err := net.SplitHostPort(control)
	if err != nil {
		return control
	}
	return net.JoinHostPort(host, itoa(port))
}

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	var buf [6]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	return string(buf[i:])
}
