package tunnel

import (
	"context"
	"fmt"
	"net"
	"testing"
	"time"
)

// startEcho is a stand-in for the game server: it answers every datagram with
// "echo:" + payload from the same socket.
func startEcho(t *testing.T) *net.UDPConn {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("echo: %v", err)
	}
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			_, _ = conn.WriteToUDP(append([]byte("echo:"), buf[:n]...), addr)
		}
	}()
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

func waitPort(c *Client, timeout time.Duration) int {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if p := c.PublicPort(); p != 0 {
			return p
		}
		time.Sleep(20 * time.Millisecond)
	}
	return 0
}

// TestRelayForwardsUDP is the regression test for the tunnel: a player sends a
// datagram to the relay's public port and receives the answer from the local
// service, exactly as a Minecraft client would.
func TestRelayForwardsUDP(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	echo := startEcho(t)
	relay := &Relay{Listen: "127.0.0.1:0", Tokens: []string{"s3cret"}}
	ready := make(chan string, 1)
	go func() {
		if err := relay.ListenAndServe(ctx, func(addr string) { ready <- addr }); err != nil {
			t.Logf("relay: %v", err)
		}
	}()

	var control string
	select {
	case control = <-ready:
	case <-time.After(5 * time.Second):
		t.Fatal("relay não abriu o listener")
	}

	client := &Client{
		RelayAddr: control,
		Token:     "s3cret",
		LocalPort: echo.LocalAddr().(*net.UDPAddr).Port,
	}
	go client.Run(ctx)

	port := waitPort(client, 5*time.Second)
	if port == 0 {
		t.Fatal("o túnel não estabeleceu sessão")
	}
	if addrs := client.PublicAddrs(); len(addrs) == 0 {
		t.Fatal("nenhum endereço público anunciado")
	}

	player, err := net.Dial("udp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatalf("player: %v", err)
	}
	defer player.Close()
	_ = player.SetDeadline(time.Now().Add(5 * time.Second))

	for round := 1; round <= 3; round++ {
		payload := fmt.Sprintf("ping%d", round)
		if _, err := player.Write([]byte(payload)); err != nil {
			t.Fatalf("envio %d: %v", round, err)
		}
		buf := make([]byte, 2048)
		n, err := player.Read(buf)
		if err != nil {
			t.Fatalf("resposta %d: %v", round, err)
		}
		if got, want := string(buf[:n]), "echo:"+payload; got != want {
			t.Fatalf("resposta %d = %q, esperado %q", round, got, want)
		}
	}
}

// TestRelayRejectsBadToken makes sure an unknown client cannot open a public
// port on the relay.
func TestRelayRejectsBadToken(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	echo := startEcho(t)
	relay := &Relay{Listen: "127.0.0.1:0", Tokens: []string{"correct"}}
	ready := make(chan string, 1)
	go func() {
		if err := relay.ListenAndServe(ctx, func(addr string) { ready <- addr }); err != nil {
			t.Logf("relay: %v", err)
		}
	}()
	var control string
	select {
	case control = <-ready:
	case <-time.After(5 * time.Second):
		t.Fatal("relay não abriu o listener")
	}

	client := &Client{
		RelayAddr: control,
		Token:     "wrong",
		LocalPort: echo.LocalAddr().(*net.UDPAddr).Port,
	}
	go client.Run(ctx)

	if port := waitPort(client, 1200*time.Millisecond); port != 0 {
		t.Fatalf("sessão estabelecida com token inválido (porta %d)", port)
	}
}

// TestParsePortRange covers the relay's port allocation flag.
func TestParsePortRange(t *testing.T) {
	cases := []struct {
		in      string
		want    [2]int
		wantErr bool
	}{
		{"", [2]int{}, false},
		{"19132", [2]int{19132, 19132}, false},
		{"19200-19300", [2]int{19200, 19300}, false},
		{"19300-19200", [2]int{}, true},
		{"0", [2]int{}, true},
		{"abc", [2]int{}, true},
	}
	for _, c := range cases {
		got, err := ParsePortRange(c.in)
		if (err != nil) != c.wantErr {
			t.Fatalf("ParsePortRange(%q) erro = %v, esperado erro = %v", c.in, err, c.wantErr)
		}
		if !c.wantErr && got != c.want {
			t.Fatalf("ParsePortRange(%q) = %v, esperado %v", c.in, got, c.want)
		}
	}
}
