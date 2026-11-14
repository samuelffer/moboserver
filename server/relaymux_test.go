package main

import (
	"context"
	"net"
	"testing"
	"time"

	"github.com/samuelffer/moboserver/server/internal/tunnel"
)

// O mux precisa anunciar exatamente as portas que o relay atribuiu, com o IP
// público, e não pode dar a mesma porta a duas conexões ICE.
func TestRelayMuxAtribuiPortasUnicas(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	relay := &tunnel.UDPRelay{Listen: "127.0.0.1:0", MaxPorts: 4}
	ready := make(chan string, 1)
	go func() { _ = relay.ListenAndServe(ctx, func(control string) { ready <- control }) }()
	control := <-ready

	client := &tunnel.UDPClient{RelayAddr: control, Ports: 2}
	assigned := make(chan []int, 1)
	client.OnReady = func(ports []int) { assigned <- ports }
	go client.Run(ctx)

	var ports []int
	select {
	case ports = <-assigned:
	case <-ctx.Done():
		t.Fatal("sem portas do relay")
	}

	publicIP := net.ParseIP("203.0.113.10")
	mux := newRelayUDPMux(client, publicIP)
	listen := mux.GetListenAddresses()
	if len(listen) != len(ports) {
		t.Fatalf("esperava %d endereços, recebi %d", len(ports), len(listen))
	}
	for i, addr := range listen {
		udp, ok := addr.(*net.UDPAddr)
		if !ok {
			t.Fatalf("endereço %d não é UDP", i)
		}
		if !udp.IP.Equal(publicIP) || udp.Port != ports[i] {
			t.Fatalf("endereço %d = %v, esperava %v:%d", i, udp, publicIP, ports[i])
		}
	}

	a, err := mux.GetConn("ufrag-a", nil)
	if err != nil {
		t.Fatal(err)
	}
	b, err := mux.GetConn("ufrag-b", nil)
	if err != nil {
		t.Fatal(err)
	}
	if a.LocalAddr().String() == b.LocalAddr().String() {
		t.Fatalf("duas conexões ICE receberam a mesma porta: %s", a.LocalAddr())
	}
	again, err := mux.GetConn("ufrag-a", nil)
	if err != nil {
		t.Fatal(err)
	}
	if again != a {
		t.Fatal("a mesma ufrag recebeu outra conexão")
	}

	mux.RemoveConnByUfrag("ufrag-a")
	c, err := mux.GetConn("ufrag-c", nil)
	if err != nil {
		t.Fatal(err)
	}
	if c.LocalAddr().String() != a.LocalAddr().String() {
		t.Fatalf("porta liberada não foi reaproveitada: %s vs %s", c.LocalAddr(), a.LocalAddr())
	}
}

// O caminho de dados: o que o ICE escreve sai da porta pública do relay e o que
// o jogador manda chega ao PacketConn com o endereço de origem correto.
func TestRelayMuxEntregaDatagramas(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	relay := &tunnel.UDPRelay{Listen: "127.0.0.1:0", MaxPorts: 1}
	ready := make(chan string, 1)
	go func() { _ = relay.ListenAndServe(ctx, func(control string) { ready <- control }) }()
	control := <-ready

	client := &tunnel.UDPClient{RelayAddr: control, Ports: 1}
	assigned := make(chan []int, 1)
	client.OnReady = func(ports []int) { assigned <- ports }
	go client.Run(ctx)

	var ports []int
	select {
	case ports = <-assigned:
	case <-ctx.Done():
		t.Fatal("sem portas do relay")
	}

	mux := newRelayUDPMux(client, net.ParseIP("203.0.113.10"))
	conn, err := mux.GetConn("ufrag-dados", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	// "Jogador" escutando em localhost.
	player, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer player.Close()

	if _, err := conn.WriteTo([]byte("check"), player.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	_ = player.SetReadDeadline(time.Now().Add(10 * time.Second))
	buf := make([]byte, 256)
	n, from, err := player.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("o jogador não recebeu o datagrama: %v", err)
	}
	if string(buf[:n]) != "check" {
		t.Fatalf("payload = %q", buf[:n])
	}
	if from.Port != ports[0] {
		t.Fatalf("o datagrama saiu da porta %d, esperava %d", from.Port, ports[0])
	}

	// Resposta do jogador deve chegar com o endereço dele.
	if _, err := player.WriteToUDP([]byte("pong"), &net.UDPAddr{
		IP: net.ParseIP("127.0.0.1"), Port: ports[0],
	}); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	n, addr, err := conn.ReadFrom(buf)
	if err != nil {
		t.Fatalf("o mux não recebeu a resposta: %v", err)
	}
	if string(buf[:n]) != "pong" {
		t.Fatalf("payload de volta = %q", buf[:n])
	}
	if addr.String() != player.LocalAddr().String() {
		t.Fatalf("origem = %v, esperava %v", addr, player.LocalAddr())
	}
}
