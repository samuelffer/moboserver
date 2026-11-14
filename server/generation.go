package main

// Política de geração por mundo, separada do provider Bedrock.
//
// O registro fica no armazenamento privado do app e é ligado ao ID persistente
// do mundo. Ele NÃO é uma prova de geração vanilla: o gerador deste projeto
// continua simplificado. A política só garante que o gerador efetivamente
// entregue ao Dragonfly seja o mesmo registrado para aquele mundo, e que uma
// divergência ou registro inválido não vire uma troca silenciosa.
//
// Fluxo: resolver -> validar/abrir o mundo e o chunk de nascimento em memória ->
// gravar atomicamente (somente se for novo) -> criar o servidor com o gerador
// efetivo. Chunks existentes nunca são apagados ou regerados por este código.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/df-mc/dragonfly/server/world"
	"github.com/df-mc/dragonfly/server/world/chunk"
	"github.com/df-mc/goleveldb/leveldb"
)

const (
	generationEngine = "moboserver-terrain-v1"
	generationSchema = 2
	maxPolicyBytes   = 16 * 1024

	originImported = "imported"
	originCreated  = "created"
	originUnknown  = "unknown"

	recoveryReset       = "reset"
	recoveryAdoptLegacy = "adopt-legacy"
)

type generationPolicy struct {
	Schema        int    `json:"schema"`
	WorldID       string `json:"world_id"`
	Engine        string `json:"engine"`
	Overworld     string `json:"overworld"`
	Origin        string `json:"origin"`
	EngineVersion string `json:"engine_version"`
}

func newGenerationPolicy(worldID, gen, origin, version string) generationPolicy {
	return generationPolicy{
		Schema: generationSchema, WorldID: worldID, Engine: generationEngine,
		Overworld: gen, Origin: origin, EngineVersion: version,
	}
}

type generationError struct {
	Code   string
	Detail string
}

func (e *generationError) Error() string { return e.Detail }

func generationErr(code, format string, args ...any) error {
	return &generationError{Code: code, Detail: fmt.Sprintf(format, args...)}
}

// generationPath usa um nome derivado do ID, nunca o ID como caminho. Dois
// mundos têm arquivos independentes; ../ e separadores não escapam da pasta.
func generationPath(dataDir, worldID string) string {
	sum := sha256.Sum256([]byte(worldID))
	return filepath.Join(dataDir, "generation", "worlds", hex.EncodeToString(sum[:])+".json")
}

func legacyGenerationPath(dataDir string) string {
	return filepath.Join(dataDir, "generation.json")
}

func validWorldID(id string) bool {
	if id == "" || id == "." || id == ".." || len(id) > 128 {
		return false
	}
	for _, r := range id {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' || r == '.') {
			return false
		}
	}
	return true
}

func (p generationPolicy) validate() error {
	if p.Schema != generationSchema {
		return fmt.Errorf("schema %d não suportado (esperado %d)", p.Schema, generationSchema)
	}
	if !validWorldID(p.WorldID) {
		return fmt.Errorf("world_id ausente ou inválido")
	}
	if p.Engine != generationEngine {
		return fmt.Errorf("engine desconhecido %q", p.Engine)
	}
	if p.Overworld != "terrain" && p.Overworld != "flat" {
		return fmt.Errorf("gerador overworld inválido %q (use terrain ou flat)", p.Overworld)
	}
	if p.Origin != originImported && p.Origin != originCreated && p.Origin != originUnknown {
		return fmt.Errorf("origem inválida %q", p.Origin)
	}
	if strings.TrimSpace(p.EngineVersion) == "" || len(p.EngineVersion) > 64 {
		return fmt.Errorf("engine_version ausente ou inválida")
	}
	return nil
}

func decodePolicyJSON(data []byte, dst any) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return err
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		if err == nil {
			return fmt.Errorf("mais de um valor JSON no registro")
		}
		return err
	}
	return nil
}

func readPolicyFile(path string) ([]byte, bool, error) {
	st, err := os.Lstat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, false, nil
		}
		return nil, false, err
	}
	if !st.Mode().IsRegular() {
		return nil, true, fmt.Errorf("registro não é um arquivo regular")
	}
	if st.Size() > maxPolicyBytes {
		return nil, true, fmt.Errorf("registro grande demais (%d bytes)", st.Size())
	}
	data, err := os.ReadFile(path)
	return data, true, err
}

// loadGenerationPolicy valida sintaxe E valores. Ausente não é erro; presente
// e inválido nunca cai para a configuração atual.
func loadGenerationPolicy(path string) (generationPolicy, bool, error) {
	data, found, err := readPolicyFile(path)
	if err != nil || !found {
		return generationPolicy{}, found, err
	}
	var p generationPolicy
	if err := decodePolicyJSON(data, &p); err != nil {
		return generationPolicy{}, true, fmt.Errorf("JSON de política inválido: %w", err)
	}
	if err := p.validate(); err != nil {
		return generationPolicy{}, true, err
	}
	return p, true, nil
}

type worldOriginMetadata struct {
	Schema     int    `json:"schema"`
	WorldID    string `json:"world_id"`
	Origin     string `json:"origin"`
	Source     string `json:"source"`
	ImportedAt int64  `json:"imported_at"`
}

// readWorldOrigin aceita somente metadados gravados pelo importador do app.
// A existência de level.dat, db/ ou original.mcworld não prova a origem.
func readWorldOrigin(path, worldID string) (string, error) {
	if path == "" {
		return "", nil
	}
	data, found, err := readPolicyFile(path)
	if err != nil {
		return "", generationErr("generation_origin_invalid", "não foi possível ler os metadados de importação: %v", err)
	}
	if !found {
		return "", generationErr("generation_origin_invalid", "metadados de importação ausentes para o mundo %s; a origem não será inventada", worldID)
	}
	var m worldOriginMetadata
	if err := decodePolicyJSON(data, &m); err != nil {
		return "", generationErr("generation_origin_invalid", "metadados de importação inválidos: %v", err)
	}
	if m.Schema != 1 || m.WorldID != worldID || m.Origin != originImported || m.Source != "mcworld-import" || m.ImportedAt <= 0 {
		return "", generationErr("generation_origin_invalid", "metadados de importação não identificam este mundo com segurança")
	}
	return originImported, nil
}

type generationResolution struct {
	Path      string
	Effective generationPolicy
	Requested generationPolicy
	Diverged  bool
	IsNew     bool
	Recovery  string

	corruptRaw []byte
}

// resolveGenerationPolicy não grava nada. O registro antigo global só é adotado
// por ação explícita para UM world_id; sem essa evidência a migração é ambígua.
func resolveGenerationPolicy(dataDir, worldID string, wanted generationPolicy, recovery string) (generationResolution, error) {
	if !validWorldID(worldID) || wanted.WorldID != worldID {
		return generationResolution{}, generationErr("generation_policy_invalid", "ID persistente do mundo ausente ou divergente")
	}
	if err := wanted.validate(); err != nil {
		return generationResolution{}, generationErr("generation_policy_invalid", "pedido de geração inválido: %v", err)
	}
	if recovery != "" && recovery != recoveryReset && recovery != recoveryAdoptLegacy {
		return generationResolution{}, generationErr("generation_policy_invalid", "recuperação de geração desconhecida %q", recovery)
	}
	path := generationPath(dataDir, worldID)
	data, found, readErr := readPolicyFile(path)
	if readErr != nil {
		return generationResolution{}, generationErr("generation_policy_invalid",
			"não foi possível ler a política do mundo %s: %v. O arquivo foi preservado; use Mundos > opções > Recuperar política.", worldID, readErr)
	}
	if found {
		var existing generationPolicy
		err := decodePolicyJSON(data, &existing)
		if err == nil {
			err = existing.validate()
		}
		if err == nil && existing.WorldID != worldID {
			err = fmt.Errorf("world_id do registro não corresponde a este mundo")
		}
		if err != nil {
			if recovery != recoveryReset {
				return generationResolution{}, generationErr("generation_policy_invalid",
					"política do mundo %s inválida: %v. Nada foi gerado nem apagado. O arquivo será preservado; use Mundos > opções > Recuperar política para redefinir conscientemente os chunks NOVOS.", worldID, err)
			}
			return generationResolution{
				Path: path, Effective: wanted, Requested: wanted, IsNew: true,
				Recovery: recoveryReset, corruptRaw: append([]byte(nil), data...),
			}, nil
		}
		if recovery == recoveryReset {
			return generationResolution{}, generationErr("generation_policy_invalid",
				"a política válida deste mundo não pode ser redefinida para contornar uma divergência")
		}
		return generationResolution{
			Path: path, Effective: existing, Requested: wanted,
			Diverged: existing.Engine != wanted.Engine || existing.Overworld != wanted.Overworld,
		}, nil
	}

	legacyData, legacyFound, err := readPolicyFile(legacyGenerationPath(dataDir))
	if err != nil {
		return generationResolution{}, generationErr("generation_policy_invalid", "registro global antigo ilegível: %v", err)
	}
	if legacyFound {
		if recovery == recoveryAdoptLegacy {
			p, err := decodeLegacyPolicy(legacyData, worldID)
			if err != nil {
				return generationResolution{}, generationErr("generation_legacy_invalid", "registro global antigo inválido: %v", err)
			}
			return generationResolution{Path: path, Effective: p, Requested: wanted, IsNew: true, Recovery: recovery,
				Diverged: p.Engine != wanted.Engine || p.Overworld != wanted.Overworld}, nil
		}
		if recovery == recoveryReset {
			// O global continua intacto e não é atribuído a nenhum outro mundo.
			return generationResolution{Path: path, Effective: wanted, Requested: wanted, IsNew: true, Recovery: recovery}, nil
		}
		return generationResolution{}, generationErr("generation_legacy_ambiguous",
			"existe um generation.json antigo, mas ele não identifica o mundo. Não vou atribuí-lo automaticamente a %s. Em Mundos > opções > Recuperar política, adote-o somente se tiver certeza de que pertence a este mundo; os chunks existentes não serão alterados.", worldID)
	}
	return generationResolution{Path: path, Effective: wanted, Requested: wanted, IsNew: true, Recovery: recovery}, nil
}

func decodeLegacyPolicy(data []byte, worldID string) (generationPolicy, error) {
	var old struct {
		Engine        string `json:"engine"`
		Overworld     string `json:"overworld"`
		Imported      bool   `json:"imported"`
		EngineVersion string `json:"engine_version"`
	}
	if err := decodePolicyJSON(data, &old); err != nil {
		return generationPolicy{}, err
	}
	p := newGenerationPolicy(worldID, old.Overworld, originUnknown, old.EngineVersion)
	p.Engine = old.Engine
	// O booleano antigo confundia existência com origem e não é evidência.
	if err := p.validate(); err != nil {
		return generationPolicy{}, err
	}
	return p, nil
}

func lockPolicyFile(path string) (*os.File, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX); err != nil {
		_ = f.Close()
		return nil, err
	}
	return f, nil
}

func unlockPolicyFile(f *os.File) {
	_ = syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
	_ = f.Close()
}

func writePolicyAtomic(path string, p generationPolicy) error {
	if err := p.validate(); err != nil {
		return err
	}
	data, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".generation-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(name, path); err != nil {
		return err
	}
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

func preserveCorruptPolicy(path string, raw []byte) (string, error) {
	backup, err := os.CreateTemp(filepath.Dir(path), filepath.Base(path)+".corrupt-*")
	if err != nil {
		return "", err
	}
	name := backup.Name()
	if _, err := backup.Write(raw); err != nil {
		_ = backup.Close()
		return name, err
	}
	if err := backup.Sync(); err != nil {
		_ = backup.Close()
		return name, err
	}
	return name, backup.Close()
}

// commitGenerationPolicy é chamado somente depois de validateWorldDir (quando
// exigido), mcdb.Open e preflightWorld. Se outro processo criou um registro no
// meio do caminho, não o sobrescrevemos.
func commitGenerationPolicy(res generationResolution) error {
	if err := res.Effective.validate(); err != nil {
		return err
	}
	lock, err := lockPolicyFile(res.Path)
	if err != nil {
		return err
	}
	defer unlockPolicyFile(lock)
	existing, found, err := loadGenerationPolicy(res.Path)
	if err != nil {
		if res.Recovery != recoveryReset {
			return err
		}
	} else if found {
		if existing != res.Effective {
			return fmt.Errorf("a política mudou durante a inicialização; tente novamente sem trocar o gerador")
		}
		return nil
	}
	if !res.IsNew {
		return nil
	}
	if res.Recovery == recoveryReset && len(res.corruptRaw) > 0 {
		if _, err := preserveCorruptPolicy(res.Path, res.corruptRaw); err != nil {
			return fmt.Errorf("não foi possível preservar o registro corrompido: %w", err)
		}
	}
	if res.Recovery == recoveryAdoptLegacy {
		data, found, err := readPolicyFile(legacyGenerationPath(filepath.Dir(filepath.Dir(filepath.Dir(res.Path)))))
		if err != nil || !found {
			return fmt.Errorf("registro global antigo mudou durante a recuperação")
		}
		p, err := decodeLegacyPolicy(data, res.Effective.WorldID)
		if err != nil || p != res.Effective {
			return fmt.Errorf("registro global antigo mudou durante a recuperação")
		}
	}
	return writePolicyAtomic(res.Path, res.Effective)
}

// preflightWorld abre/decodifica o chunk de nascimento ou o gera SOMENTE em
// memória. Não chama StoreColumn: nenhum chunk existente é regravado aqui.
func preflightWorld(provider world.Provider, gen string) error {
	if provider == nil {
		return fmt.Errorf("provider do mundo ausente")
	}
	settings := provider.Settings()
	if settings == nil {
		return fmt.Errorf("metadados do mundo ausentes")
	}
	spawn := settings.Spawn
	pos := world.ChunkPos{int32(spawn[0] >> 4), int32(spawn[2] >> 4)}
	for _, dim := range []world.Dimension{world.Overworld, world.Nether, world.End} {
		_, err := provider.LoadColumn(pos, dim)
		if err == nil {
			continue
		}
		if !errors.Is(err, leveldb.ErrNotFound) {
			return fmt.Errorf("mundo não passou na validação de abertura do chunk %v (%v): %w", pos, dim, err)
		}
		g := generatorFor(gen)(dim)
		if g == nil {
			return fmt.Errorf("gerador efetivo ausente para %v", dim)
		}
		c := chunk.New(world.DefaultBlockRegistry, dim.Range())
		g.GenerateChunk(pos, c)
	}
	return nil
}

// requestedWorldOrigin separa existência de origem. Um mundo antigo sem
// metadados confiáveis permanece unknown, mesmo que tenha level.dat e db/.
func requestedWorldOrigin(opts options, existed bool) (string, error) {
	origin, err := readWorldOrigin(opts.WorldOriginFile, opts.WorldID)
	if err != nil {
		return "", err
	}
	if origin != "" {
		return origin, nil
	}
	if existed {
		return originUnknown, nil
	}
	return originCreated, nil
}

// policyForStart é o ponto único de resolução antes de criar o servidor.
func policyForStart(opts options, existed bool) (generationResolution, error) {
	origin, err := requestedWorldOrigin(opts, existed)
	if err != nil {
		return generationResolution{}, err
	}
	gen := strings.ToLower(strings.TrimSpace(opts.Gen))
	wanted := newGenerationPolicy(opts.WorldID, gen, origin, appVersion)
	return resolveGenerationPolicy(opts.DataDir, opts.WorldID, wanted, opts.GenerationRecovery)
}

func originLabel(origin string) string {
	switch origin {
	case originImported:
		return "importado (metadados da importação)"
	case originCreated:
		return "criado pelo servidor"
	default:
		return "origem desconhecida"
	}
}
