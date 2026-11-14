package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	df "github.com/df-mc/dragonfly/server"
	"github.com/df-mc/dragonfly/server/block"
	"github.com/df-mc/dragonfly/server/block/cube"
	"github.com/df-mc/dragonfly/server/world"
	"github.com/df-mc/dragonfly/server/world/chunk"
	"github.com/df-mc/dragonfly/server/world/mcdb"
	"github.com/google/uuid"
)

func testPolicy(id, gen, origin string) generationPolicy {
	return newGenerationPolicy(id, gen, origin, "1.4.4-test")
}

// createValidPolicyWorld usa o provider real do Dragonfly: level.dat + LevelDB
// + um chunk persistido. O bloco de diamante é uma sentinela contra regeneração.
func createValidPolicyWorld(t *testing.T, dir string) {
	t.Helper()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	db, err := mcdb.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	settings := db.Settings()
	settings.Name = filepath.Base(dir)
	settings.Spawn = cube.Pos{0, 80, 0}
	db.SaveSettings(settings)
	pos := world.ChunkPos{0, 0}
	c := chunk.New(world.DefaultBlockRegistry, world.Overworld.Range())
	generatorFor("flat")(world.Overworld).GenerateChunk(pos, c)
	c.SetBlock(0, int16(c.Range().Min()+1), 0, 0, world.DefaultBlockRegistry.BlockRuntimeID(block.Diamond{}))
	if err := db.StoreColumn(pos, world.Overworld, &chunk.Column{Chunk: c}); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	if v := validateWorldDir(dir); !v.OK {
		t.Fatalf("fixture inválida: %+v", v)
	}
}

func freePolicyPort(t *testing.T) int {
	t.Helper()
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	port := c.LocalAddr().(*net.UDPAddr).Port
	_ = c.Close()
	return port
}

func policyOptions(t *testing.T, dataDir, id, gen string) options {
	t.Helper()
	dir := filepath.Join(dataDir, "worlds", id)
	createValidPolicyWorld(t, dir)
	return options{
		World: dir, WorldID: id, DataDir: dataDir,
		PlayersDir: filepath.Join(dataDir, "players", id),
		BansFile:   filepath.Join(dataDir, "bans-"+id+".json"),
		Name:       "policy-test", Port: freePolicyPort(t), MaxPlayers: 1,
		Auth: false, GameMode: "survival", Difficulty: "peaceful",
		ViewDistance: 4, Gen: gen, RequireWorld: true,
		NetherNet: false, RakNet: true, LogLevel: "error", ignoreStdin: true,
	}
}

// startPolicyServer executa o caminho real de inicialização, sem cliente
// Minecraft e sem rede externa. Devolve o servidor já criado para inspecionar
// chunks pelo gerador que foi efetivamente entregue ao Config.
func startPolicyServer(t *testing.T, opts options) (*df.Server, func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	ready := make(chan *df.Server, 1)
	done := make(chan error, 1)
	opts.readyHook = func(srv *df.Server) { ready <- srv }
	go func() { done <- runServer(ctx, opts, slog.New(eventHandler{}), cancel) }()
	select {
	case srv := <-ready:
		return srv, func() {
			cancel()
			select {
			case err := <-done:
				if err != nil {
					t.Errorf("shutdown: %v", err)
				}
			case <-time.After(15 * time.Second):
				t.Error("timeout no shutdown")
			}
		}
	case err := <-done:
		cancel()
		t.Fatalf("servidor não iniciou: %v", err)
	case <-time.After(30 * time.Second):
		cancel()
		t.Fatal("timeout esperando servidor")
	}
	return nil, func() {}
}

func assertPolicyError(t *testing.T, err error, code string) {
	t.Helper()
	var ge *generationError
	if !errors.As(err, &ge) || ge.Code != code {
		t.Fatalf("esperava generationError %s, veio %v", code, err)
	}
}

func TestGenerationPolicyWorldsIndependentAndApplied(t *testing.T) {
	dataDir := t.TempDir()
	a := policyOptions(t, dataDir, "world-a", "terrain")
	b := policyOptions(t, dataDir, "world-b", "flat")

	srvA, stopA := startPolicyServer(t, a)
	stopA()
	srvB, stopB := startPolicyServer(t, b)
	stopB()
	_ = srvA
	_ = srvB

	pa, _, err := loadGenerationPolicy(generationPath(dataDir, a.WorldID))
	if err != nil || pa.Overworld != "terrain" {
		t.Fatalf("política A: %+v, %v", pa, err)
	}
	pb, _, err := loadGenerationPolicy(generationPath(dataDir, b.WorldID))
	if err != nil || pb.Overworld != "flat" {
		t.Fatalf("política B: %+v, %v", pb, err)
	}
	if generationPath(dataDir, a.WorldID) == generationPath(dataDir, b.WorldID) {
		t.Fatal("mundos diferentes compartilharam o registro")
	}

	// Pedir flat para A não pode trocar o gerador executado.
	a.Gen = "flat"
	srv, stop := startPolicyServer(t, a)
	defer stop()
	pos := world.ChunkPos{10, 10}
	expected := chunk.New(world.DefaultBlockRegistry, world.Overworld.Range())
	generatorFor("terrain")(world.Overworld).GenerateChunk(pos, expected)
	flat := chunk.New(world.DefaultBlockRegistry, world.Overworld.Range())
	generatorFor("flat")(world.Overworld).GenerateChunk(pos, flat)
	foundDifference := false
	for x := uint8(0); x < 16 && !foundDifference; x++ {
		for z := uint8(0); z < 16 && !foundDifference; z++ {
			for y := flat.Range().Min() + 6; y < flat.Range().Max(); y++ {
				want := expected.Block(x, int16(y), z, 0)
				if want == 0 || want == flat.Block(x, int16(y), z, 0) {
					continue
				}
				got, err := world.Call(context.Background(), srv.World(), func(tx *world.Tx) (uint32, error) {
					return world.BlockRuntimeID(tx.Block(cube.Pos{int(pos[0])*16 + int(x), y, int(pos[1])*16 + int(z)})), nil
				})
				if err != nil {
					t.Fatal(err)
				}
				if got != want {
					t.Fatalf("chunk novo de A não usou terrain: bloco em %d,%d,%d = %d, esperado %d", x, y, z, got, want)
				}
				foundDifference = true
				break
			}
		}
	}
	if !foundDifference {
		t.Fatal("os geradores de teste não produziram diferença observável")
	}
	// O chunk já existente mantém a sentinela, sem regeneração.
	got, err := world.Call(context.Background(), srv.World(), func(tx *world.Tx) (uint32, error) {
		return world.BlockRuntimeID(tx.Block(cube.Pos{0, tx.Range().Min() + 1, 0})), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if want := world.DefaultBlockRegistry.BlockRuntimeID(block.Diamond{}); got != want {
		t.Fatalf("chunk existente foi alterado: %d != %d", got, want)
	}
	pa2, _, _ := loadGenerationPolicy(generationPath(dataDir, a.WorldID))
	if pa2.Overworld != "terrain" {
		t.Fatalf("pedido flat sobrescreveu A: %+v", pa2)
	}
}

func TestGenerationPolicyInvalidNeverGeneratesSilently(t *testing.T) {
	cases := map[string]string{
		"json quebrado":   `{isso nao e json`,
		"engine estranho": `{"schema":2,"world_id":"bad","engine":"vanilla","overworld":"flat","origin":"unknown","engine_version":"1.4.4"}`,
		"gerador vazio":   `{"schema":2,"world_id":"bad","engine":"moboserver-terrain-v1","overworld":"","origin":"unknown","engine_version":"1.4.4"}`,
		"schema antigo":   `{"schema":1,"world_id":"bad","engine":"moboserver-terrain-v1","overworld":"flat","origin":"unknown","engine_version":"1.4.4"}`,
		"outro mundo":     `{"schema":2,"world_id":"other","engine":"moboserver-terrain-v1","overworld":"flat","origin":"unknown","engine_version":"1.4.4"}`,
		"null":            `null`,
		"dois valores":    `{} {}`,
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			dataDir := t.TempDir()
			opts := policyOptions(t, dataDir, "bad", "terrain")
			path := generationPath(dataDir, "bad")
			if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte(raw), 0o600); err != nil {
				t.Fatal(err)
			}
			err := runServer(context.Background(), opts, slog.New(eventHandler{}), func() {})
			assertPolicyError(t, err, "generation_policy_invalid")
			after, readErr := os.ReadFile(path)
			if readErr != nil || string(after) != raw {
				t.Fatalf("registro inválido não foi preservado: %q, %v", after, readErr)
			}
		})
	}
}

func TestGenerationPolicyCorruptRecoveryIsExplicitAndPreservesFile(t *testing.T) {
	dataDir := t.TempDir()
	opts := policyOptions(t, dataDir, "recover", "flat")
	path := generationPath(dataDir, "recover")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	raw := []byte(`{"engine":?}`)
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := policyForStart(opts, true); err == nil {
		t.Fatal("registro corrompido não pode iniciar sem recuperação")
	}
	opts.GenerationRecovery = recoveryReset
	res, err := policyForStart(opts, true)
	if err != nil {
		t.Fatal(err)
	}
	// Resolver ainda não grava: a validação do mundo acontece antes do commit.
	if got, _ := os.ReadFile(path); string(got) != string(raw) {
		t.Fatal("resolução gravou antes da validação")
	}
	if v := validateWorldDir(opts.World); !v.OK {
		t.Fatal(v.Detail)
	}
	if err := commitGenerationPolicy(res); err != nil {
		t.Fatal(err)
	}
	backups, _ := filepath.Glob(path + ".corrupt-*")
	if len(backups) != 1 {
		t.Fatalf("esperava uma cópia do corrompido, veio %v", backups)
	}
	if got, _ := os.ReadFile(backups[0]); string(got) != string(raw) {
		t.Fatal("backup não preservou os bytes originais")
	}
	p, found, err := loadGenerationPolicy(path)
	if err != nil || !found || p.Overworld != "flat" || p.Origin != originUnknown {
		t.Fatalf("política recuperada: %+v, %v", p, err)
	}
	opts.GenerationRecovery = "" // ação única já consumida pelo commit acima
	srv, stop := startPolicyServer(t, opts)
	_ = srv
	stop()
}

func TestGenerationPolicyExistingWorldIsNotAutomaticallyImported(t *testing.T) {
	dataDir := t.TempDir()
	opts := policyOptions(t, dataDir, "old", "terrain")
	// Nem a cópia antiga do .mcworld é tratada como metadado confiável.
	if err := os.WriteFile(filepath.Join(opts.World, "original.mcworld"), []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	srv, stop := startPolicyServer(t, opts)
	_ = srv
	stop()
	p, _, err := loadGenerationPolicy(generationPath(dataDir, "old"))
	if err != nil || p.Origin != originUnknown {
		t.Fatalf("mundo existente virou importado por existir: %+v, %v", p, err)
	}

	originPath := filepath.Join(dataDir, "origin.json")
	m := worldOriginMetadata{Schema: 1, WorldID: "imported", Origin: originImported, Source: "mcworld-import", ImportedAt: time.Now().UnixMilli()}
	data, _ := json.Marshal(m)
	if err := os.WriteFile(originPath, data, 0o600); err != nil {
		t.Fatal(err)
	}
	imp := policyOptions(t, dataDir, "imported", "flat")
	imp.WorldOriginFile = originPath
	srv2, stop2 := startPolicyServer(t, imp)
	_ = srv2
	stop2()
	p2, _, err := loadGenerationPolicy(generationPath(dataDir, "imported"))
	if err != nil || p2.Origin != originImported {
		t.Fatalf("metadado de importação não chegou à política: %+v, %v", p2, err)
	}
}

func TestGenerationPolicyValidationFailureLeavesNoRecord(t *testing.T) {
	dataDir := t.TempDir()
	opts := policyOptions(t, dataDir, "broken", "flat")
	// O validador estrutural vê arquivos em db/, mas o LevelDB não abre.
	if err := os.WriteFile(filepath.Join(opts.World, "db", "CURRENT"), []byte("not a leveldb manifest"), 0o600); err != nil {
		t.Fatal(err)
	}
	err := runServer(context.Background(), opts, slog.New(eventHandler{}), func() {})
	if err == nil {
		t.Fatal("mundo inválido iniciou")
	}
	if _, found, loadErr := loadGenerationPolicy(generationPath(dataDir, "broken")); loadErr != nil || found {
		t.Fatalf("falha de validação deixou registro: found=%v err=%v", found, loadErr)
	}
}

func TestGenerationPolicyLegacyMigrationIsAmbiguousUntilExplicit(t *testing.T) {
	dataDir := t.TempDir()
	a := policyOptions(t, dataDir, "legacy-a", "flat")
	b := policyOptions(t, dataDir, "legacy-b", "flat")
	legacy := `{"engine":"moboserver-terrain-v1","overworld":"terrain","imported":true,"engine_version":"1.3.0"}`
	if err := os.WriteFile(legacyGenerationPath(dataDir), []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	err := runServer(context.Background(), a, slog.New(eventHandler{}), func() {})
	assertPolicyError(t, err, "generation_legacy_ambiguous")
	if _, found, _ := loadGenerationPolicy(generationPath(dataDir, a.WorldID)); found {
		t.Fatal("migração ambígua criou registro automático")
	}
	a.GenerationRecovery = recoveryAdoptLegacy
	srv, stop := startPolicyServer(t, a)
	_ = srv
	stop()
	pa, _, err := loadGenerationPolicy(generationPath(dataDir, a.WorldID))
	if err != nil || pa.Overworld != "terrain" || pa.Origin != originUnknown || pa.EngineVersion != "1.3.0" {
		t.Fatalf("adoção explícita não preservou o gerador/versão e não marcou origem desconhecida: %+v, %v", pa, err)
	}
	err = runServer(context.Background(), b, slog.New(eventHandler{}), func() {})
	assertPolicyError(t, err, "generation_legacy_ambiguous")
	if got, _ := os.ReadFile(legacyGenerationPath(dataDir)); string(got) != legacy {
		t.Fatal("registro global foi alterado/apagado")
	}
}

func TestGenerationPolicyRequestedValuesAreValidated(t *testing.T) {
	for _, gen := range []string{"", "vanilla"} {
		opts := options{WorldID: "x", DataDir: t.TempDir(), Gen: gen}
		if _, err := policyForStart(opts, true); err == nil {
			t.Fatalf("pedido %q aceito", gen)
		}
	}
}

func TestGenerationPolicyAtomicWriteLeavesNoTemporaryFile(t *testing.T) {
	dir := t.TempDir()
	p := testPolicy("atomic", "terrain", originUnknown)
	path := generationPath(dir, p.WorldID)
	res, err := resolveGenerationPolicy(dir, p.WorldID, p, "")
	if err != nil {
		t.Fatal(err)
	}
	if err := commitGenerationPolicy(res); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".generation-") {
			t.Fatalf("temporário ficou para trás: %s", e.Name())
		}
	}
	got, found, err := loadGenerationPolicy(path)
	if err != nil || !found || got != p {
		t.Fatalf("registro atômico não recarregou: %+v, %v", got, err)
	}
}

func TestGenerationPolicyDivergenceKeepsEffectiveGenerator(t *testing.T) {
	dir := t.TempDir()
	first := testPolicy("diverge", "terrain", originUnknown)
	res, err := resolveGenerationPolicy(dir, first.WorldID, first, "")
	if err != nil {
		t.Fatal(err)
	}
	if err := commitGenerationPolicy(res); err != nil {
		t.Fatal(err)
	}
	other := testPolicy("diverge", "flat", originUnknown)
	res, err = resolveGenerationPolicy(dir, other.WorldID, other, "")
	if err != nil {
		t.Fatal(err)
	}
	if !res.Diverged || res.Effective.Overworld != "terrain" {
		t.Fatalf("divergência não preservou o gerador: %+v", res)
	}
	if gen := generatorFor(res.Effective.Overworld)(world.Overworld); gen == nil {
		t.Fatal("gerador efetivo ausente")
	}
	if err := commitGenerationPolicy(res); err != nil {
		t.Fatal(err)
	}
	got, _, _ := loadGenerationPolicy(generationPath(dir, "diverge"))
	if got.Overworld != "terrain" {
		t.Fatalf("commit da divergência alterou política: %+v", got)
	}
}

func TestGenerationPolicyMissingIsNotAnError(t *testing.T) {
	dir := t.TempDir()
	_, found, err := loadGenerationPolicy(generationPath(dir, "missing"))
	if err != nil || found {
		t.Fatalf("ausente virou erro/registro: %v, %v", err, found)
	}
}

func TestGenerationPolicyPathCannotEscape(t *testing.T) {
	dir := t.TempDir()
	for _, id := range []string{"../escape", "a/b", "..", ""} {
		if validWorldID(id) {
			t.Fatalf("ID inseguro aceito: %q", id)
		}
	}
	path := generationPath(dir, "safe-id")
	if !strings.HasPrefix(path, filepath.Join(dir, "generation", "worlds")+string(os.PathSeparator)) {
		t.Fatalf("caminho fora do armazenamento próprio: %s", path)
	}
}

// noWriteProvider falha se qualquer escrita acontecer durante o preflight.
type noWriteProvider struct {
	world.Provider
	writes int
}

func (p *noWriteProvider) StoreColumn(world.ChunkPos, world.Dimension, *chunk.Column) error {
	p.writes++
	return errors.New("preflight não pode gravar chunks")
}
func (p *noWriteProvider) SaveSettings(*world.Settings) { p.writes++ }
func (p *noWriteProvider) SavePlayerSpawnPosition(uuid.UUID, cube.Pos) error {
	p.writes++
	return errors.New("preflight não pode gravar spawn")
}

func TestPreflightWorldNeverStoresColumns(t *testing.T) {
	base := world.NopProvider{Set: &world.Settings{Spawn: cube.Pos{32, 80, -48}}}
	p := &noWriteProvider{Provider: base}
	if err := preflightWorld(p, "terrain"); err != nil {
		t.Fatal(err)
	}
	if p.writes != 0 {
		t.Fatalf("preflight escreveu %d vez(es)", p.writes)
	}
}

func TestTransportSummaryDoesNotAnnounceUDPWhenRakNetOff(t *testing.T) {
	opts := options{Port: 19132, RakNet: false}
	got := transportSummary("1.26.60", 2225, opts, []string{"nethernet"}, 40000, 40100)
	if strings.Contains(got, "RakNet ativo") || strings.Contains(got, "UDP :19132") {
		t.Fatalf("anunciou UDP legado com RakNet off: %s", got)
	}
	if !strings.Contains(got, "RakNet desligado") || !strings.Contains(got, "sinalização TCP") {
		t.Fatalf("resumo incompleto: %s", got)
	}
	opts.RakNet = true
	got = transportSummary("1.26.60", 2225, opts, []string{"raknet"}, 0, 0)
	if !strings.Contains(got, "RakNet ativo: UDP :19132") {
		t.Fatalf("RakNet ativo não anunciado: %s", got)
	}
}

func TestClampViewDistance(t *testing.T) {
	for _, tc := range []struct{ in, want int }{{0, 4}, {4, 4}, {8, 8}, {24, 24}, {99, 24}} {
		if got := clampViewDistance(tc.in); got != tc.want {
			t.Fatalf("clamp(%d)=%d, esperado %d", tc.in, got, tc.want)
		}
	}
}
