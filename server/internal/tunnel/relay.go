package tunnel

import (
	"bufio"
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Relay accepts tunnel sessions from phones and forwards player datagrams to
// them. It is the only component that needs a public address.
type Relay struct {
	// Listen is the TCP address for control connections, e.g. ":9000".
	Listen string
	// Tokens are the shared secrets a client may present. An empty list accepts
	// every client and is meant for tests on localhost only.
	Tokens []string
	// PortRange optionally restricts the public UDP ports handed out, so the
	// host firewall can be opened for a fixed range (e.g. 19200-19300).
	PortRange [2]int
	// FlowIdle is how long a player flow may stay silent before it is closed.
	FlowIdle time.Duration
	// Log receives one-line diagnostics.
	Log func(string)

	mu       sync.Mutex
	listener net.Listener
	sessions int
}

func (r *Relay) logf(format string, args ...any) {
	if r.Log != nil {
		r.Log(fmt.Sprintf(format, args...))
	}
}

// ListenAndServe blocks until ctx is cancelled. ready is called with the
// control address once the relay is accepting sessions.
func (r *Relay) ListenAndServe(ctx context.Context, ready func(control string)) error {
	if r.FlowIdle <= 0 {
		r.FlowIdle = 60 * time.Second
	}
	ln, err := net.Listen("tcp", r.Listen)
	if err != nil {
		return fmt.Errorf("relay: listen %s: %w", r.Listen, err)
	}
	r.mu.Lock()
	r.listener = ln
	r.mu.Unlock()
	if ready != nil {
		ready(ln.Addr().String())
	}
	r.logf("relay escutando em %s", ln.Addr())

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return nil
			}
			r.logf("relay: accept: %v", err)
			continue
		}
		go r.serve(ctx, conn)
	}
}

// accepts compares the presented token against every configured one in
// constant time.
func (r *Relay) accepts(token string) bool {
	if len(r.Tokens) == 0 {
		return true
	}
	presented := []byte(token)
	for _, allowed := range r.Tokens {
		if subtle.ConstantTimeCompare([]byte(allowed), presented) == 1 {
			return true
		}
	}
	return false
}

// Addr returns the control address, useful in tests.
func (r *Relay) Addr() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.listener == nil {
		return ""
	}
	return r.listener.Addr().String()
}

type flow struct {
	id   uint16
	addr *net.UDPAddr
	seen time.Time
}

func (r *Relay) serve(ctx context.Context, conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))

	reader := bufio.NewReader(conn)
	line, err := reader.ReadString('\n')
	if err != nil {
		return
	}
	line = strings.TrimSpace(line)
	if !strings.HasPrefix(line, handshakePrefix) {
		_, _ = conn.Write([]byte("ERR handshake\n"))
		return
	}
	token := strings.TrimSpace(strings.TrimPrefix(line, handshakePrefix))
	if !r.accepts(token) {
		_, _ = conn.Write([]byte("ERR token\n"))
		r.logf("relay: sessão rejeitada (token inválido) de %s", conn.RemoteAddr())
		return
	}

	udp, err := r.openUDP()
	if err != nil {
		_, _ = conn.Write([]byte("ERR porta\n"))
		r.logf("relay: sem porta pública: %v", err)
		return
	}
	defer udp.Close()
	_ = conn.SetDeadline(time.Time{})

	publicPort := udp.LocalAddr().(*net.UDPAddr).Port
	if _, err := fmt.Fprintf(conn, "OK %d\n", publicPort); err != nil {
		return
	}
	r.mu.Lock()
	r.sessions++
	count := r.sessions
	r.mu.Unlock()
	r.logf("relay: sessão de %s na porta UDP %d (sessões ativas: %d)", conn.RemoteAddr(), publicPort, count)
	defer func() {
		r.mu.Lock()
		r.sessions--
		r.mu.Unlock()
		r.logf("relay: sessão de %s encerrada", conn.RemoteAddr())
	}()

	var writeMu sync.Mutex
	writeFrameLocked := func(typ byte, id uint16, payload []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		return writeFrame(conn, typ, id, payload)
	}

	flows := make(map[uint16]*flow)
	byAddr := make(map[string]uint16)
	var flowsMu sync.Mutex
	nextID := uint16(1)

	// Player -> phone.
	go func() {
		buf := make([]byte, maxFrameSize)
		for {
			n, addr, err := udp.ReadFromUDP(buf)
			if err != nil {
				return
			}
			key := addr.String()
			flowsMu.Lock()
			id, ok := byAddr[key]
			if !ok {
				for tries := 0; tries < 65535; tries++ {
					if _, used := flows[nextID]; !used && nextID != 0 {
						break
					}
					nextID++
				}
				if nextID == 0 {
					flowsMu.Unlock()
					continue
				}
				id = nextID
				flows[id] = &flow{id: id, addr: addr, seen: time.Now()}
				byAddr[key] = id
				nextID++
			}
			if f := flows[id]; f != nil {
				f.seen = time.Now()
			}
			flowsMu.Unlock()

			payload := make([]byte, n)
			copy(payload, buf[:n])
			if err := writeFrameLocked(frameData, id, payload); err != nil {
				return
			}
		}
	}()

	// Expire idle flows so the phone can close its sockets.
	stopJanitor := make(chan struct{})
	defer close(stopJanitor)
	go func() {
		ticker := time.NewTicker(r.FlowIdle / 2)
		defer ticker.Stop()
		for {
			select {
			case <-stopJanitor:
				return
			case <-ticker.C:
				now := time.Now()
				flowsMu.Lock()
				for id, f := range flows {
					if now.Sub(f.seen) > r.FlowIdle {
						delete(flows, id)
						delete(byAddr, f.addr.String())
						_ = writeFrameLocked(frameClose, id, nil)
					}
				}
				flowsMu.Unlock()
			}
		}
	}()

	// Phone -> player.
	for {
		typ, id, payload, err := readFrame(reader)
		if err != nil {
			return
		}
		switch typ {
		case frameData:
			flowsMu.Lock()
			f := flows[id]
			var addr *net.UDPAddr
			if f != nil {
				f.seen = time.Now()
				addr = f.addr
			}
			flowsMu.Unlock()
			if addr != nil {
				_, _ = udp.WriteToUDP(payload, addr)
			}
		case frameClose:
			flowsMu.Lock()
			if f := flows[id]; f != nil {
				delete(byAddr, f.addr.String())
				delete(flows, id)
			}
			flowsMu.Unlock()
		}
	}
}

func (r *Relay) openUDP() (*net.UDPConn, error) {
	if r.PortRange[0] <= 0 || r.PortRange[1] < r.PortRange[0] {
		return net.ListenUDP("udp", &net.UDPAddr{})
	}
	for port := r.PortRange[0]; port <= r.PortRange[1]; port++ {
		conn, err := net.ListenUDP("udp", &net.UDPAddr{Port: port})
		if err == nil {
			return conn, nil
		}
	}
	return nil, fmt.Errorf("nenhuma porta livre em %d-%d", r.PortRange[0], r.PortRange[1])
}

// ParsePortRange reads "19200-19300" into a range; empty means ephemeral.
func ParsePortRange(value string) ([2]int, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return [2]int{}, nil
	}
	parts := strings.SplitN(value, "-", 2)
	min, err := strconv.Atoi(strings.TrimSpace(parts[0]))
	if err != nil {
		return [2]int{}, fmt.Errorf("faixa de portas inválida: %q", value)
	}
	max := min
	if len(parts) == 2 {
		if max, err = strconv.Atoi(strings.TrimSpace(parts[1])); err != nil {
			return [2]int{}, fmt.Errorf("faixa de portas inválida: %q", value)
		}
	}
	if min < 1 || max > 65535 || max < min {
		return [2]int{}, fmt.Errorf("faixa de portas inválida: %q", value)
	}
	return [2]int{min, max}, nil
}
