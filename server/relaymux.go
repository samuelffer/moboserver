package main

// Mux UDP do ICE sobre o túnel (MOBOTUN/2).
//
// O Pion entrega todos os datagramas de uma conexão ICE ao PacketConn devolvido
// por ice.UDPMux.GetConn. Aqui esse PacketConn não fala com um soquete local: ao
// escrever, ele entrega o datagrama ao túnel com o endereço do jogador; ao ler,
// recebe o datagrama que o relay emitiu do endereço público daquela porta.
//
// Consequências (todas desejadas):
//   - o candidato anunciado é IP público + porta do relay, obtido de
//     GetListenAddresses()/LocalAddr — sem editar SDP nem tocar em fingerprint;
//   - o celular nunca precisa de porta de entrada na operadora (CGNAT);
//   - cada conexão ICE tem a sua porta pública, então a associação
//     jogador↔porta↔resposta é preservada;
//   - a LAN continua usando o listener normal: este mux é opcional e só entra
//     quando o operador configura o relay.

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/samuelffer/moboserver/server/internal/tunnel"
)

// relayUDPMux implementa ice.UDPMux sobre uma sessão MOBOTUN/2.
type relayUDPMux struct {
	client   *tunnel.UDPClient
	publicIP net.IP

	mu      sync.Mutex
	conns   map[string]*relayConn // ufrag -> conexão
	byIndex map[int]string        // porta -> ufrag
	closed  bool
}

func newRelayUDPMux(client *tunnel.UDPClient, publicIP net.IP) *relayUDPMux {
	return &relayUDPMux{
		client:   client,
		publicIP: publicIP,
		conns:    make(map[string]*relayConn),
		byIndex:  make(map[int]string),
	}
}

// GetListenAddresses devolve os endereços públicos atribuídos pelo relay. O
// Pion usa esta lista para os candidatos host; por isso o IP e a porta já são
// os alcançáveis de fora.
func (m *relayUDPMux) GetListenAddresses() []net.Addr {
	ports := m.client.AssignedPorts()
	out := make([]net.Addr, 0, len(ports))
	for _, port := range ports {
		out = append(out, &net.UDPAddr{IP: m.publicIP, Port: port})
	}
	return out
}

// GetConn devolve (ou cria) o PacketConn da conexão ICE identificada por ufrag.
// `addr` é o endereço local escolhido pelo Pion; quando ele corresponde a uma
// porta da faixa, aquela porta é usada.
func (m *relayUDPMux) GetConn(ufrag string, addr net.Addr) (net.PacketConn, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return nil, errors.New("relay mux: fechado")
	}
	if conn, ok := m.conns[ufrag]; ok {
		return conn, nil
	}
	ports := m.client.AssignedPorts()
	if len(ports) == 0 {
		return nil, errors.New("relay mux: nenhuma porta atribuída pelo relay")
	}

	index := -1
	if udp, ok := addr.(*net.UDPAddr); ok {
		for i, port := range ports {
			if port == udp.Port {
				index = i
				break
			}
		}
	}
	if index < 0 {
		for i := range ports {
			if _, used := m.byIndex[i]; !used {
				index = i
				break
			}
		}
	}
	if index < 0 {
		return nil, fmt.Errorf("relay mux: as %d portas já estão em uso", len(ports))
	}
	m.byIndex[index] = ufrag
	conn := &relayConn{
		mux:   m,
		index: index,
		local: &net.UDPAddr{IP: m.publicIP, Port: ports[index]},
	}
	m.conns[ufrag] = conn
	return conn, nil
}

// RemoveConnByUfrag libera a porta quando o Pion termina a conexão.
func (m *relayUDPMux) RemoveConnByUfrag(ufrag string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	conn, ok := m.conns[ufrag]
	if !ok {
		return
	}
	delete(m.conns, ufrag)
	delete(m.byIndex, conn.index)
	conn.markClosed()
}

// Close encerra todos os PacketConns. A sessão do túnel é gerida pelo chamador.
func (m *relayUDPMux) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closed = true
	for _, conn := range m.conns {
		conn.markClosed()
	}
	m.conns = make(map[string]*relayConn)
	m.byIndex = make(map[int]string)
	return nil
}

// relayConn é o PacketConn de uma conexão ICE.
type relayConn struct {
	mux   *relayUDPMux
	index int
	local *net.UDPAddr

	mu            sync.Mutex
	closed        bool
	readDeadline  time.Time
	writeDeadline time.Time
}

func (c *relayConn) markClosed() {
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
}

// ReadFrom entrega o próximo datagrama vindo desta porta, com o endereço real
// do jogador que o enviou.
func (c *relayConn) ReadFrom(p []byte) (int, net.Addr, error) {
	deadline := c.readDeadlineValue()
	ctx := context.Background()
	var cancel context.CancelFunc = func() {}
	if !deadline.IsZero() {
		ctx, cancel = context.WithDeadline(ctx, deadline)
	}
	defer cancel()

	addr, payload, err := c.mux.client.Read(ctx, c.index)
	if err != nil {
		return 0, nil, err
	}
	n := copy(p, payload)
	return n, addr, nil
}

// WriteTo envia o datagrama pelo túnel; o relay o emite da porta pública.
func (c *relayConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	udp, ok := addr.(*net.UDPAddr)
	if !ok {
		resolved, err := net.ResolveUDPAddr("udp", addr.String())
		if err != nil {
			return 0, fmt.Errorf("relay mux: endereço de destino: %w", err)
		}
		udp = resolved
	}
	if err := c.mux.client.Write(c.index, udp, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *relayConn) Close() error {
	c.markClosed()
	return nil
}

func (c *relayConn) LocalAddr() net.Addr { return c.local }

func (c *relayConn) SetDeadline(t time.Time) error {
	c.mu.Lock()
	c.readDeadline = t
	c.writeDeadline = t
	c.mu.Unlock()
	return nil
}

func (c *relayConn) SetReadDeadline(t time.Time) error {
	c.mu.Lock()
	c.readDeadline = t
	c.mu.Unlock()
	return nil
}

func (c *relayConn) SetWriteDeadline(t time.Time) error {
	c.mu.Lock()
	c.writeDeadline = t
	c.mu.Unlock()
	return nil
}

func (c *relayConn) readDeadlineValue() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.readDeadline
}
