package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/df-mc/dragonfly/server/player"
	"github.com/df-mc/dragonfly/server/player/chat"
	"github.com/df-mc/dragonfly/server/player/skin"
	"github.com/df-mc/dragonfly/server/world"
	"github.com/google/uuid"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/login"
)

// playerInfo is the JSON shape shared with the Android app.
//
// Ping is the round-trip time reported by the session in milliseconds, or -1
// when the transport does not expose a measurement (the app then shows "Sem
// medição" instead of inventing a value). Transport is filled only when the
// version of Dragonfly exposes which listener accepted the connection.
type playerInfo struct {
	Name      string `json:"name"`
	XUID      string `json:"xuid"`
	Device    string `json:"device"`
	Addr      string `json:"addr"`
	Since     int64  `json:"since"`
	Ping      int64  `json:"ping"`
	Transport string `json:"transport,omitempty"`
}

type playerEntry struct {
	playerInfo
	handle     *world.EntityHandle
	avatarHash string
	avatarRev  int64
}

// registry keeps track of the players that are currently online. Player
// objects are only valid inside Dragonfly callbacks, so only the entity handle
// and plain strings are stored.
type registry struct {
	mu sync.RWMutex
	m  map[uuid.UUID]*playerEntry
}

func newRegistry() *registry { return &registry{m: map[uuid.UUID]*playerEntry{}} }

func (r *registry) add(id uuid.UUID, h *world.EntityHandle, info playerInfo) playerInfo {
	r.mu.Lock()
	defer r.mu.Unlock()
	info.Ping = -1
	r.m[id] = &playerEntry{playerInfo: info, handle: h}
	return info
}

// setPing stores the latest latency sample for one player.
func (r *registry) setPing(id uuid.UUID, ms int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if e, ok := r.m[id]; ok {
		e.Ping = ms
	}
}

func (r *registry) setAvatar(id uuid.UUID, hash string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if e, ok := r.m[id]; ok {
		e.avatarHash = hash
	}
}

// nextAvatarRev avanca a revisao do avatar do jogador. A revisao permite ao app
// descartar uma imagem antiga que terminou de decodificar depois de uma troca
// de skin.
func (r *registry) nextAvatarRev(id uuid.UUID) int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if e, ok := r.m[id]; ok {
		e.avatarRev++
		return e.avatarRev
	}
	return 0
}

func (r *registry) avatarHash(id uuid.UUID) string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if e, ok := r.m[id]; ok {
		return e.avatarHash
	}
	return ""
}

// handles returns a snapshot of the entity handles by player id.
func (r *registry) handles() map[uuid.UUID]*world.EntityHandle {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make(map[uuid.UUID]*world.EntityHandle, len(r.m))
	for id, e := range r.m {
		out[id] = e.handle
	}
	return out
}

// ids returns the ids of the players that have an avatar cached.
func (r *registry) ids() []uuid.UUID {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]uuid.UUID, 0, len(r.m))
	for id := range r.m {
		out = append(out, id)
	}
	return out
}

func (r *registry) remove(id uuid.UUID) (playerInfo, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.m[id]
	if !ok {
		return playerInfo{}, false
	}
	delete(r.m, id)
	return e.playerInfo, true
}

func (r *registry) byName(name string) (*playerEntry, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, e := range r.m {
		if strings.EqualFold(e.Name, name) {
			return e, true
		}
	}
	return nil, false
}

func (r *registry) snapshot() []playerInfo {
	r.mu.RLock()
	out := make([]playerInfo, 0, len(r.m))
	for _, e := range r.m {
		out = append(out, e.playerInfo)
	}
	r.mu.RUnlock()
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

// joinHandler is installed on every player. It keeps the registry and the app
// in sync (quit, skin changes).
type joinHandler struct {
	player.NopHandler
	rt *runtime
}

// HandleSkinChange re-renders the avatar when the player changes skin during the
// session. Dragonfly delivers the new skin here, so the thumbnail never goes
// stale while the player is online.
func (h *joinHandler) HandleSkinChange(ctx *player.Context, s *skin.Skin) {
	if h.rt == nil || s == nil {
		return
	}
	p := ctx.Player()
	h.rt.emitAvatar(p.UUID(), p.Name(), p.XUID(), *s, true)
}

// HandleChat entrega ao app as mensagens escritas pelos jogadores. A mensagem
// continua indo para o chat global do jogo (o handler padrão não é substituído
// aqui), portanto o app não precisa deduzir chat a partir do log.
func (h *joinHandler) HandleChat(ctx *player.Context, message *string) {
	if h.rt == nil || message == nil {
		return
	}
	p := ctx.Player()
	text := strings.TrimSpace(*message)
	if text == "" {
		return
	}
	emit(map[string]any{
		"t":       "chat",
		"from":    p.Name(),
		"xuid":    p.XUID(),
		"msg":     text,
		"session": h.rt.startedAt,
	})
}

func (h *joinHandler) HandleQuit(p *player.Player) {
	info, ok := h.rt.reg.remove(p.UUID())
	if !ok {
		return
	}
	emitLog("info", fmt.Sprintf("%s saiu do servidor", info.Name))
	emit(map[string]any{"t": "quit", "name": info.Name, "xuid": info.XUID})
}

// command is a single control instruction written to the server's stdin by the
// Android app, one JSON object per line.
type command struct {
	Cmd  string `json:"cmd"`
	Name string `json:"name"`
	XUID string `json:"xuid"`
	Msg  string `json:"msg"`
	// ID identifica o pedido de chat do app e volta em "chat_sent", para a
	// interface não marcar como enviada uma mensagem que se perdeu.
	ID string `json:"id"`
}

func (rt *runtime) commandLoop(ctx context.Context) {
	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for sc.Scan() {
		select {
		case <-ctx.Done():
			return
		default:
		}
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		var c command
		if err := json.Unmarshal([]byte(line), &c); err != nil {
			emitLog("warn", "Comando inválido recebido do app")
			continue
		}
		rt.handleCommand(c)
	}
	if rt.opts.ignoreStdin {
		<-ctx.Done()
		return
	}
	// stdin closed means the app process is gone. Shut down so the world is
	// saved instead of being killed mid-write.
	select {
	case <-ctx.Done():
	default:
		emitLog("warn", "Conexão com o aplicativo encerrada: salvando e desligando.")
		rt.stop()
	}
}

func (rt *runtime) handleCommand(c command) {
	switch strings.ToLower(strings.TrimSpace(c.Cmd)) {
	case "players":
		rt.emitPlayers()
	case "kick":
		if ok := rt.kick(c.Name, "Você foi expulso do servidor."); ok {
			emitLog("info", c.Name+" foi expulso do servidor.")
		}
	case "ban":
		rt.ban(c.Name, c.XUID)
	case "unban":
		if err := rt.bans.remove(c.XUID); err != nil {
			emitLog("error", "Não foi possível remover o banimento: "+err.Error())
		} else {
			emitLog("info", "Banimento removido para o XUID "+c.XUID)
		}
	case "avatar":
		rt.resendAvatar(c.Name)
	case "avatar_export":
		rt.exportAvatar(c.Name, c.ID)
	case "say":
		msg := strings.TrimSpace(c.Msg)
		if msg == "" {
			return
		}
		chat.Global.WriteString("§7[Servidor] §f" + msg)
		// A confirmação só sai depois de o chat aceitar a escrita; o app fica
		// com o texto intacto se nenhum "chat_sent" chegar.
		emit(map[string]any{"t": "chat_sent", "id": c.ID, "ok": true, "msg": msg})
		emitLog("info", "[Servidor] "+msg)
	case "save":
		if rt.saveAll() {
			emitLog("info", "Mundo salvo em disco.")
		} else {
			emitLog("error", "Falha ao salvar o mundo em disco.")
		}
	case "stop":
		rt.stop()
	default:
		emitLog("warn", "Comando desconhecido: "+c.Cmd)
	}
}

// resendAvatar reenvia a miniatura quando o app pede (evento perdido, app
// aberto depois do join ou consumidor sem a imagem). E sob demanda: o tick de
// ping não carrega PNG.
func (rt *runtime) resendAvatar(name string) {
	entry, ok := rt.reg.byName(name)
	if !ok {
		emitLog("debug", "Avatar pedido para jogador que não está online: "+name)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	var id uuid.UUID
	s, err := player.Call(ctx, entry.handle, func(tx *world.Tx, p *player.Player) (skin.Skin, error) {
		id = p.UUID()
		return p.Skin(), nil
	})
	if err != nil {
		emitLog("warn", "Não foi possível reenviar o avatar de "+entry.Name+": "+err.Error())
		return
	}
	rt.emitAvatar(id, entry.Name, entry.XUID, s, true)
}

// exportAvatar é explicitamente pedido pelo usuário. Envia apenas PNG da
// textura e os bytes do modelo recebidos nesta sessão, sem FullID/login/tokens.
func (rt *runtime) exportAvatar(name, requestID string) {
	entry, ok := rt.reg.byName(name)
	if !ok {
		emit(map[string]any{"t": "avatar_export", "id": requestID, "name": name, "ok": false, "reason": "jogador não está mais online"})
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s, err := player.Call(ctx, entry.handle, func(tx *world.Tx, p *player.Player) (skin.Skin, error) {
		return p.Skin(), nil
	})
	if err != nil {
		emit(map[string]any{"t": "avatar_export", "id": requestID, "name": name, "ok": false, "reason": "não foi possível ler a skin da sessão: " + err.Error()})
		return
	}
	export := buildSkinExport(s)
	emit(map[string]any{
		"t": "avatar_export", "id": requestID, "name": name, "ok": true,
		"texture": base64.StdEncoding.EncodeToString(export.TexturePNG),
		"model":   base64.StdEncoding.EncodeToString(export.ModelJSON),
		"info":    export.Info, "reason": export.Reason,
	})
}

func (rt *runtime) kick(name, reason string) bool {
	entry, ok := rt.reg.byName(name)
	if !ok {
		emitLog("warn", "Jogador não encontrado: "+name)
		return false
	}
	if err := rt.disconnect(entry.handle, reason); err != nil {
		emitLog("error", "Falha ao expulsar "+entry.Name+": "+err.Error())
		return false
	}
	return true
}

func (rt *runtime) ban(name, xuid string) {
	if xuid == "" {
		entry, ok := rt.reg.byName(name)
		if !ok {
			emitLog("warn", "Para banir alguém offline informe o XUID. Jogador não encontrado: "+name)
			return
		}
		xuid = entry.XUID
		name = entry.Name
	}
	if xuid == "" {
		emitLog("warn", "Este jogador não tem XUID (autenticação desligada); não é possível banir.")
		return
	}
	if err := rt.bans.add(xuid, name); err != nil {
		emitLog("error", "Não foi possível salvar o banimento: "+err.Error())
		return
	}
	rt.disconnect(rt.handleByName(name), "§cVocê foi banido deste servidor.")
	emitLog("info", fmt.Sprintf("%s foi banido permanentemente.", name))
	rt.emitPlayers()
}

func (rt *runtime) handleByName(name string) *world.EntityHandle {
	if e, ok := rt.reg.byName(name); ok {
		return e.handle
	}
	return nil
}

func (rt *runtime) disconnect(h *world.EntityHandle, reason string) error {
	if h == nil {
		return fmt.Errorf("jogador não está mais online")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	_, err := player.Call(ctx, h, func(tx *world.Tx, p *player.Player) (struct{}, error) {
		p.Disconnect(reason)
		return struct{}{}, nil
	})
	return err
}

// saveAll grava os mundos e só então anuncia o salvamento concluído. O app
// mostra "Último salvamento" a partir deste evento; nenhuma mensagem de log é
// usada como prova de sucesso.
func (rt *runtime) saveAll() bool {
	ok := true
	for _, w := range []*world.World{rt.srv.World(), rt.srv.Nether(), rt.srv.End()} {
		if w == nil {
			continue
		}
		if saved := func() (saved bool) {
			defer func() {
				if recover() != nil {
					saved = false
				}
			}()
			w.Save()
			return true
		}(); !saved {
			ok = false
		}
	}
	if !ok {
		emit(map[string]any{"t": "save_failed"})
		return false
	}
	emit(map[string]any{"t": "saved", "at": time.Now().UnixMilli()})
	return true
}

// banList is a small persistent set of banned XUIDs. It is stored as JSON next
// to the world so bans survive app restarts.
type banList struct {
	mu   sync.RWMutex
	path string
	m    map[string]string // xuid -> name
}

type banFileEntry struct {
	XUID string `json:"xuid"`
	Name string `json:"name"`
}

func loadBanList(path string) (*banList, error) {
	b := &banList{path: path, m: map[string]string{}}
	if path == "" {
		return b, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return b, nil
		}
		return b, err
	}
	var entries []banFileEntry
	if err := json.Unmarshal(data, &entries); err != nil {
		return b, err
	}
	for _, e := range entries {
		if e.XUID != "" {
			b.m[e.XUID] = e.Name
		}
	}
	return b, nil
}

func (b *banList) contains(xuid string) bool {
	if b == nil || xuid == "" {
		return false
	}
	b.mu.RLock()
	defer b.mu.RUnlock()
	_, ok := b.m[xuid]
	return ok
}

func (b *banList) add(xuid, name string) error {
	b.mu.Lock()
	b.m[xuid] = name
	err := b.persistLocked()
	b.mu.Unlock()
	return err
}

func (b *banList) remove(xuid string) error {
	b.mu.Lock()
	delete(b.m, xuid)
	err := b.persistLocked()
	b.mu.Unlock()
	return err
}

func (b *banList) persistLocked() error {
	if b.path == "" {
		return nil
	}
	entries := make([]banFileEntry, 0, len(b.m))
	for xuid, name := range b.m {
		entries = append(entries, banFileEntry{XUID: xuid, Name: name})
	}
	data, err := json.MarshalIndent(entries, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(b.path), 0o755); err != nil {
		return err
	}
	tmp := b.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, b.path)
}

// deviceOSName maps the numeric device id sent by the client to something the
// player recognises. The client picks this value itself, so it is informational
// only — never used to make security decisions.
func deviceOSName(id protocol.DeviceOS) string {
	switch id {
	case 1:
		return "Android"
	case 2:
		return "iOS"
	case 3:
		return "macOS"
	case 4:
		return "Fire OS"
	case 7:
		return "Windows 10/11"
	case 8:
		return "Windows (Win32)"
	case 10:
		return "Apple TV"
	case 11:
		return "PlayStation"
	case 12:
		return "Nintendo Switch"
	case 13:
		return "Xbox"
	case 15:
		return "Linux"
	default:
		return fmt.Sprintf("dispositivo %d", int(id))
	}
}

// banAllower rejects connections of banned XUIDs before they spawn.
type banAllower struct {
	bans *banList
}

func (a *banAllower) Allow(addr net.Addr, d login.IdentityData, c login.ClientData) (string, bool) {
	// Logging here is the first point where the game (not just RakNet) is
	// talking to us, so it proves the whole path works: DNS, NAT/firewall and
	// the login handshake. It is also where the client reports its version.
	where := "endereco desconhecido"
	if addr != nil {
		where = addr.String()
	}
	client := strings.TrimSpace(c.GameVersion)
	if client == "" {
		client = "nao informado"
	}
	device := strings.TrimSpace(c.DeviceModel)
	if device == "" {
		device = deviceOSName(c.DeviceOS)
	}
	emitLog("info", fmt.Sprintf("Conexao recebida de %s • cliente Minecraft %s (%s) • servidor fala %s, protocolo %d",
		where, client, device, protocol.CurrentVersion, protocol.CurrentProtocol))

	if a.bans != nil && a.bans.contains(d.XUID) {
		return "§cVocê foi banido deste servidor.", false
	}
	return "", true
}
