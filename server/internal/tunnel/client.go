package tunnel

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Client keeps an outbound tunnel to the relay and forwards player datagrams to
// the local game server.
type Client struct {
	// RelayAddr is "host:port" of the relay control endpoint.
	RelayAddr string
	// Token authenticates this session on the relay.
	Token string
	// LocalPort is the UDP port of the game server on this device.
	LocalPort int
	// UseTLS wraps the control connection in TLS (recommended with a domain).
	UseTLS bool
	// ServerName overrides the TLS server name; defaults to the relay host.
	ServerName string
	// OnState reports "connecting", "connected", "reconnecting" or "error".
	OnState func(state, detail string)
	// Log receives one-line diagnostics.
	Log func(string)

	mu          sync.Mutex
	publicPort  int
	publicAddrs []string
}

// PublicPort is the UDP port the relay assigned, or 0 before connecting.
func (c *Client) PublicPort() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.publicPort
}

// PublicAddrs lists "ip:port" candidates players can use. It is filled from the
// addresses the control connection resolved to, so no DNS is needed on the
// relay side.
func (c *Client) PublicAddrs() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]string, len(c.publicAddrs))
	copy(out, c.publicAddrs)
	return out
}

func (c *Client) logf(format string, args ...any) {
	if c.Log != nil {
		c.Log(fmt.Sprintf(format, args...))
	}
}

func (c *Client) state(state, detail string) {
	if c.OnState != nil {
		c.OnState(state, detail)
	}
}

// Run reconnects forever with exponential backoff until ctx is cancelled. The
// game server keeps running regardless: the tunnel is an extra path in, never a
// dependency of LAN play.
func (c *Client) Run(ctx context.Context) {
	backoff := time.Second
	for ctx.Err() == nil {
		started := time.Now()
		err := c.session(ctx)
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			c.logf("túnel: %v", err)
		}
		if time.Since(started) > 60*time.Second {
			backoff = time.Second
		}
		c.state("reconnecting", "reconectando em "+backoff.Round(time.Second).String())
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
			if backoff > 30*time.Second {
				backoff = 30 * time.Second
			}
		}
	}
}

func (c *Client) dial(ctx context.Context) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: 15 * time.Second}
	if !c.UseTLS {
		return dialer.DialContext(ctx, "tcp", c.RelayAddr)
	}
	host, _, err := net.SplitHostPort(c.RelayAddr)
	if err != nil {
		return nil, fmt.Errorf("endereço do relay inválido: %w", err)
	}
	serverName := c.ServerName
	if serverName == "" {
		serverName = host
	}
	return tls.DialWithDialer(dialer, "tcp", c.RelayAddr, &tls.Config{
		ServerName: serverName,
		MinVersion: tls.VersionTLS12,
	})
}

func (c *Client) session(ctx context.Context) error {
	c.state("connecting", "")
	conn, err := c.dial(ctx)
	if err != nil {
		return fmt.Errorf("conectar em %s: %w", c.RelayAddr, err)
	}
	defer conn.Close()

	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
		case <-stop:
		}
	}()

	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	if _, err := fmt.Fprintf(conn, "%s%s\n", handshakePrefix, c.Token); err != nil {
		return fmt.Errorf("handshake: %w", err)
	}
	reply, err := readLine(conn)
	if err != nil {
		return fmt.Errorf("handshake: %w", err)
	}
	if strings.HasPrefix(reply, "ERR") {
		return fmt.Errorf("relay recusou a sessão: %s", strings.TrimSpace(strings.TrimPrefix(reply, "ERR")))
	}
	if !strings.HasPrefix(reply, "OK ") {
		return fmt.Errorf("resposta inesperada do relay: %q", reply)
	}
	publicPort, err := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(reply, "OK ")))
	if err != nil || publicPort <= 0 {
		return fmt.Errorf("porta pública inválida: %q", reply)
	}
	_ = conn.SetDeadline(time.Time{})

	host, _, err := net.SplitHostPort(c.RelayAddr)
	if err != nil {
		return err
	}
	c.mu.Lock()
	c.publicPort = publicPort
	c.publicAddrs = []string{net.JoinHostPort(host, strconv.Itoa(publicPort))}
	c.mu.Unlock()

	c.state("connected", net.JoinHostPort(host, strconv.Itoa(publicPort)))
	c.logf("túnel: sessão estabelecida, porta pública UDP %d", publicPort)

	var writeMu sync.Mutex
	writeFrameLocked := func(typ byte, id uint16, payload []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		return writeFrame(conn, typ, id, payload)
	}

	flows := make(map[uint16]*flowSocket)
	var flowsMu sync.Mutex
	defer func() {
		flowsMu.Lock()
		for id, f := range flows {
			f.close()
			delete(flows, id)
		}
		flowsMu.Unlock()
		c.mu.Lock()
		c.publicPort = 0
		c.publicAddrs = nil
		c.mu.Unlock()
	}()

	reader := newFrameReader(conn)
	for {
		typ, id, payload, err := readFrame(reader)
		if err != nil {
			return err
		}
		switch typ {
		case frameData:
			flowsMu.Lock()
			f := flows[id]
			if f == nil {
				created, err := c.newFlowSocket(id, writeFrameLocked)
				if err != nil {
					flowsMu.Unlock()
					c.logf("túnel: fluxo %d: %v", id, err)
					continue
				}
				flows[id] = created
				f = created
			}
			flowsMu.Unlock()
			if _, err := f.conn.Write(payload); err != nil {
				f.close()
				flowsMu.Lock()
				delete(flows, id)
				flowsMu.Unlock()
			}
		case frameClose:
			flowsMu.Lock()
			if f := flows[id]; f != nil {
				f.close()
				delete(flows, id)
			}
			flowsMu.Unlock()
		}
	}
}

// flowSocket is one UDP socket towards the local game server. A dedicated
// socket per flow is what keeps players apart: RakNet identifies a connection
// by its source address, so all players sharing one socket would be merged into
// a single client.
type flowSocket struct {
	conn   net.Conn
	closer func()
}

func (f *flowSocket) close() {
	if f.closer != nil {
		f.closer()
	}
	_ = f.conn.Close()
}

func (c *Client) newFlowSocket(id uint16, writeFrame func(byte, uint16, []byte) error) (*flowSocket, error) {
	target := net.JoinHostPort("127.0.0.1", strconv.Itoa(c.LocalPort))
	conn, err := net.Dial("udp", target)
	if err != nil {
		return nil, err
	}
	f := &flowSocket{conn: conn}
	go func() {
		buf := make([]byte, maxFrameSize)
		for {
			n, err := conn.Read(buf)
			if err != nil {
				_ = writeFrame(frameClose, id, nil)
				return
			}
			payload := make([]byte, n)
			copy(payload, buf[:n])
			if err := writeFrame(frameData, id, payload); err != nil {
				return
			}
		}
	}()
	return f, nil
}
