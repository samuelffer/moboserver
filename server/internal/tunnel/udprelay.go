package tunnel

// Relay UDP por faixa de portas (MOBOTUN/2).
//
// O túnel MOBOTUN/1 entrega uma única porta pública e multiplexa jogadores por
// endereço de origem; isso basta para RakNet, mas não para o NetherNet, cujos
// candidatos ICE são endereços distintos. O modo descrito aqui reserva uma
// faixa de portas na VPS e associa **cada porta a uma conexão ICE**: o agente do
// celular lê/escreve datagramas por índice de porta, com o endereço do jogador
// junto de cada datagrama.
//
//	MOBOTUN/2 <token> <n>\n       (celular → relay: quero n portas)
//	OK <p1>,<p2>,...\n            (relay → celular: portas atribuídas)
//	ERR <motivo>\n
//
// Depois do handshake o controle carrega quadros `framePeer`:
//
//	[1 byte tamanho do endereço][endereço texto][datagrama]
//
// O índice da porta viaja no campo `flow` do quadro existente. Com isso o
// relay emite do endereço público exato que o ICE anunciou e devolve ao
// celular o endereço real de quem enviou — a associação conexão↔destino é
// preservada nas duas direções.

import (
	"bufio"
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// HandshakeUDP é a primeira linha do cliente no modo por faixa de portas.
	HandshakeUDP = "MOBOTUN/2 "

	// framePeer carrega um datagrama com o endereço de origem/destino.
	framePeer byte = 3
)

// encodePeer monta o corpo de um quadro framePeer.
func encodePeer(addr net.Addr, payload []byte) ([]byte, error) {
	text := addr.String()
	if len(text) == 0 || len(text) > 63 {
		return nil, fmt.Errorf("endereço inválido: %q", text)
	}
	body := make([]byte, 0, 1+len(text)+len(payload))
	body = append(body, byte(len(text)))
	body = append(body, text...)
	body = append(body, payload...)
	return body, nil
}

// decodePeer separa endereço e datagrama.
func decodePeer(body []byte) (*net.UDPAddr, []byte, error) {
	if len(body) < 2 {
		return nil, nil, errors.New("quadro de jogador truncado")
	}
	size := int(body[0])
	if size == 0 || 1+size > len(body) {
		return nil, nil, errors.New("endereço inválido no quadro")
	}
	addr, err := net.ResolveUDPAddr("udp", string(body[1:1+size]))
	if err != nil {
		return nil, nil, fmt.Errorf("endereço do jogador: %w", err)
	}
	return addr, body[1+size:], nil
}

// UDPRelay expõe uma faixa de portas UDP públicas para sessões MOBOTUN/2.
type UDPRelay struct {
	// Listen é o endereço TCP de controle, por exemplo ":9000".
	Listen string
	// Tokens aceitos; vazio aceita qualquer cliente (somente testes em localhost).
	Tokens []string
	// PortRange limita as portas públicas (0,0 usa portas efêmeras).
	PortRange [2]int
	// MaxPorts é o teto de portas por sessão.
	MaxPorts int
	// Log recebe diagnóstico de uma linha.
	Log func(string)

	mu       sync.Mutex
	listener net.Listener
	sessions int
}

func (r *UDPRelay) logf(format string, args ...any) {
	if r.Log != nil {
		r.Log(fmt.Sprintf(format, args...))
	}
}

// ListenAndServe bloqueia até o contexto ser cancelado.
func (r *UDPRelay) ListenAndServe(ctx context.Context, ready func(control string)) error {
	if r.MaxPorts <= 0 {
		r.MaxPorts = 8
	}
	ln, err := net.Listen("tcp", r.Listen)
	if err != nil {
		return fmt.Errorf("relay UDP: escutar %s: %w", r.Listen, err)
	}
	r.mu.Lock()
	r.listener = ln
	r.mu.Unlock()
	if ready != nil {
		ready(ln.Addr().String())
	}
	r.logf("relay UDP escutando em %s", ln.Addr())
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
			r.logf("relay UDP: accept: %v", err)
			continue
		}
		go r.serve(ctx, conn)
	}
}

func (r *UDPRelay) accepts(token string) bool {
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

// Addr devolve o endereço de controle (usado nos testes).
func (r *UDPRelay) Addr() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.listener == nil {
		return ""
	}
	return r.listener.Addr().String()
}

// openPorts abre n sockets UDP dentro da faixa configurada.
func (r *UDPRelay) openPorts(n int) ([]*net.UDPConn, error) {
	if n <= 0 || n > r.MaxPorts {
		return nil, fmt.Errorf("quantidade de portas inválida: %d", n)
	}
	conns := make([]*net.UDPConn, 0, n)
	for i := 0; i < n; i++ {
		addr := &net.UDPAddr{}
		if r.PortRange[0] != 0 {
			addr.Port = r.PortRange[0] + i
			if r.PortRange[1] != 0 && addr.Port > r.PortRange[1] {
				break
			}
		}
		conn, err := net.ListenUDP("udp", addr)
		if err != nil {
			for _, c := range conns {
				_ = c.Close()
			}
			return nil, fmt.Errorf("abrir porta UDP %d: %w", addr.Port, err)
		}
		conns = append(conns, conn)
	}
	if len(conns) == 0 {
		return nil, errors.New("nenhuma porta UDP disponível na faixa")
	}
	return conns, nil
}

func (r *UDPRelay) serve(ctx context.Context, control net.Conn) {
	defer control.Close()
	_ = control.SetDeadline(time.Now().Add(20 * time.Second))

	reader := bufio.NewReader(control)
	line, err := reader.ReadString('\n')
	if err != nil {
		return
	}
	line = strings.TrimSpace(line)
	if !strings.HasPrefix(line, HandshakeUDP) {
		_, _ = io.WriteString(control, "ERR handshake\n")
		return
	}
	rest := strings.TrimSpace(strings.TrimPrefix(line, HandshakeUDP))
	parts := strings.Fields(rest)
	// O token é opcional (relay sem autenticação, usado em testes locais).
	var token, countText string
	switch len(parts) {
	case 1:
		token, countText = "", parts[0]
	case 2:
		token, countText = parts[0], parts[1]
	default:
		_, _ = io.WriteString(control, "ERR formato\n")
		return
	}
	if !r.accepts(token) {
		_, _ = io.WriteString(control, "ERR token\n")
		r.logf("relay UDP: sessão rejeitada de %s", control.RemoteAddr())
		return
	}
	count, err := strconv.Atoi(countText)
	if err != nil {
		_, _ = io.WriteString(control, "ERR formato\n")
		return
	}

	conns, err := r.openPorts(count)
	if err != nil {
		_, _ = io.WriteString(control, "ERR portas\n")
		r.logf("relay UDP: %v", err)
		return
	}
	defer func() {
		for _, c := range conns {
			_ = c.Close()
		}
	}()

	ports := make([]string, len(conns))
	for i, c := range conns {
		ports[i] = strconv.Itoa(c.LocalAddr().(*net.UDPAddr).Port)
	}
	if _, err := fmt.Fprintf(control, "OK %s\n", strings.Join(ports, ",")); err != nil {
		return
	}
	_ = control.SetDeadline(time.Time{})

	r.mu.Lock()
	r.sessions++
	active := r.sessions
	r.mu.Unlock()
	r.logf("relay UDP: sessão de %s com %d portas (ativas: %d)", control.RemoteAddr(), len(conns), active)
	defer func() {
		r.mu.Lock()
		r.sessions--
		r.mu.Unlock()
	}()

	var writeMu sync.Mutex
	write := func(typ byte, index uint16, body []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		return writeFrame(control, typ, index, body)
	}

	// Jogador → celular.
	for i, conn := range conns {
		go func(index int, conn *net.UDPConn) {
			buf := make([]byte, maxFrameSize)
			for {
				n, addr, err := conn.ReadFromUDP(buf)
				if err != nil {
					return
				}
				body, err := encodePeer(addr, buf[:n])
				if err != nil {
					continue
				}
				if err := write(framePeer, uint16(index), body); err != nil {
					return
				}
			}
		}(i, conn)
	}

	// Celular → jogador.
	for {
		typ, index, body, err := readFrame(reader)
		if err != nil {
			return
		}
		if typ != framePeer {
			continue
		}
		addr, payload, err := decodePeer(body)
		if err != nil {
			continue
		}
		if int(index) >= len(conns) {
			continue
		}
		_, _ = conns[index].WriteToUDP(payload, addr)
	}
}

// UDPClient é o lado do celular: fala MOBOTUN/2 e expõe as portas atribuídas.
type UDPClient struct {
	// RelayAddr é "host:porta" do controle.
	RelayAddr string
	// Token autentica a instalação no relay.
	Token string
	// Ports é quantas portas públicas o cliente pede.
	Ports int
	// UseTLS não é aplicado aqui: o modo por faixa usa a mesma conexão de
	// controle do túnel e é habilitado por quem o constrói.
	//
	// OnReady recebe as portas públicas assim que o relay responde.
	OnReady func(ports []int)
	// OnDrop é chamado quando a sessão cai (o Run reconecta).
	OnDrop func(reason string)
	// Log recebe diagnóstico de uma linha.
	Log func(string)

	mu      sync.Mutex
	writeMu sync.Mutex
	conn    net.Conn
	ports   []int
	inbound map[int]chan inboundDatagram
}

type inboundDatagram struct {
	index   int
	addr    *net.UDPAddr
	payload []byte
}

func (c *UDPClient) logf(format string, args ...any) {
	if c.Log != nil {
		c.Log(fmt.Sprintf(format, args...))
	}
}

// Run mantém a sessão viva com espera progressiva até o contexto terminar.
func (c *UDPClient) Run(ctx context.Context) {
	backoff := time.Second
	for ctx.Err() == nil {
		started := time.Now()
		err := c.session(ctx)
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			c.logf("relay UDP: %v", err)
		}
		if c.OnDrop != nil {
			c.OnDrop("sessão encerrada")
		}
		if time.Since(started) > time.Minute {
			backoff = time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
		}
	}
}

func (c *UDPClient) session(ctx context.Context) error {
	conn, err := net.DialTimeout("tcp", c.RelayAddr, 10*time.Second)
	if err != nil {
		return fmt.Errorf("conectar ao relay: %w", err)
	}
	defer conn.Close()

	ports := c.Ports
	if ports <= 0 {
		ports = 1
	}
	// Sem token a linha leva só a quantidade de portas (relay local de teste).
	handshake := HandshakeUDP
	if c.Token != "" {
		handshake += c.Token + " "
	}
	handshake += strconv.Itoa(ports) + "\n"
	if _, err := conn.Write([]byte(handshake)); err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	reader := bufio.NewReader(conn)
	line, err := reader.ReadString('\n')
	if err != nil {
		return err
	}
	line = strings.TrimSpace(line)
	if strings.HasPrefix(line, "ERR ") {
		return fmt.Errorf("relay recusou: %s", strings.TrimPrefix(line, "ERR "))
	}
	if !strings.HasPrefix(line, "OK ") {
		return fmt.Errorf("resposta inesperada do relay: %q", line)
	}
	var assigned []int
	for _, item := range strings.Split(strings.TrimSpace(strings.TrimPrefix(line, "OK ")), ",") {
		port, err := strconv.Atoi(item)
		if err != nil {
			return fmt.Errorf("porta inválida do relay: %q", item)
		}
		assigned = append(assigned, port)
	}
	_ = conn.SetDeadline(time.Time{})

	inbound := make(map[int]chan inboundDatagram, len(assigned))
	for i := range assigned {
		inbound[i] = make(chan inboundDatagram, 64)
	}
	c.mu.Lock()
	c.conn = conn
	c.ports = assigned
	c.inbound = inbound
	c.mu.Unlock()
	if c.OnReady != nil {
		c.OnReady(assigned)
	}
	c.logf("relay UDP: %d portas atribuídas", len(assigned))

	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			typ, index, body, err := readFrame(reader)
			if err != nil {
				for _, ch := range inbound {
					close(ch)
				}
				return
			}
			if typ != framePeer {
				continue
			}
			addr, payload, err := decodePeer(body)
			if err != nil {
				continue
			}
			// Cada porta tem a sua fila: um leitor de uma conexão ICE nunca
			// consome (nem descarta) datagramas de outra.
			ch, ok := inbound[int(index)]
			if !ok {
				continue
			}
			select {
			case ch <- inboundDatagram{index: int(index), addr: addr, payload: payload}:
			default:
				// Fila cheia: perder um datagrama é melhor do que travar a sessão.
			}
		}
	}()
	select {
	case <-ctx.Done():
	case <-done:
		return errors.New("conexão de controle encerrada")
	}
	return nil
}

// AssignedPorts devolve as portas públicas atribuídas.
func (c *UDPClient) AssignedPorts() []int {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]int, len(c.ports))
	copy(out, c.ports)
	return out
}

// Read devolve o próximo datagrama da porta indicada (bloqueia até ctx terminar).
func (c *UDPClient) Read(ctx context.Context, index int) (*net.UDPAddr, []byte, error) {
	c.mu.Lock()
	inbound := c.inbound[index]
	c.mu.Unlock()
	if inbound == nil {
		return nil, nil, errors.New("relay UDP: sessão ainda não estabelecida")
	}
	for {
		select {
		case <-ctx.Done():
			return nil, nil, ctx.Err()
		case item, ok := <-inbound:
			if !ok {
				return nil, nil, errors.New("relay UDP: sessão encerrada")
			}
			return item.addr, item.payload, nil
		}
	}
}

// Write envia um datagrama pela porta pública indicada para o endereço dado.
func (c *UDPClient) Write(index int, addr *net.UDPAddr, payload []byte) error {
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return errors.New("relay UDP: sessão ainda não estabelecida")
	}
	body, err := encodePeer(addr, payload)
	if err != nil {
		return err
	}
	// O mesmo controle é compartilhado por todas as conexões ICE.
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	return writeFrame(conn, framePeer, uint16(index), body)
}
