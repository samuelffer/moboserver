package main

import (
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"

	"github.com/df-mc/dragonfly/server/world/mcdb/leveldat"
	"github.com/sandertv/gophertunnel/minecraft/nbt"
)

// editorTag is the exact tag reported by the user's world (Minecraft 26.63
// writes it when the world is opened in the editor build).
const editorTag = ".editorLevelMigrationVersion"

// buildLevelDat writes a level.dat with the given data and appends the extra
// TAG_Int tag, exactly like a newer Minecraft build does.
func buildLevelDat(t *testing.T, dir string, data leveldat.Data, extraInt int32) string {
	t.Helper()
	var ld leveldat.LevelDat
	if err := ld.Marshal(&data); err != nil {
		t.Fatalf("marshal level.dat: %v", err)
	}
	buf := &fixedBuffer{}
	if err := ld.Write(buf); err != nil {
		t.Fatalf("write level.dat: %v", err)
	}
	raw := buf.Bytes()
	payload := raw[8:]
	version := binary.LittleEndian.Uint32(raw[0:4])

	spans, end, err := nbtScan(payload)
	if err != nil {
		t.Fatalf("scan: %v", err)
	}
	_ = spans

	name := editorTag
	tag := make([]byte, 0, 3+len(name)+4)
	tag = append(tag, 0x03)
	lenBytes := make([]byte, 2)
	binary.LittleEndian.PutUint16(lenBytes, uint16(len(name)))
	tag = append(tag, lenBytes...)
	tag = append(tag, []byte(name)...)
	value := make([]byte, 4)
	binary.LittleEndian.PutUint32(value, uint32(extraInt))
	tag = append(tag, value...)

	merged := make([]byte, 0, len(payload)+len(tag))
	merged = append(merged, payload[:end]...)
	merged = append(merged, tag...)
	merged = append(merged, payload[end:]...)

	path := filepath.Join(dir, "level.dat")
	if err := writeLevelDat(path, int32(version), merged); err != nil {
		t.Fatalf("write level.dat: %v", err)
	}
	t.Logf("DEBUG payload len=%d rootEnd=%d firstType=%#x lastSpan=%+v inserted=%x",
		len(merged), end, merged[0], spans[len(spans)-1], tag)
	t.Logf("DEBUG head=%x tail=%x", merged[:12], merged[end-6:])
	return path
}

type fixedBuffer struct{ data []byte }

func (b *fixedBuffer) Write(p []byte) (int, error) {
	b.data = append(b.data, p...)
	return len(p), nil
}

func (b *fixedBuffer) Bytes() []byte { return b.data }

// TestLevelDatAcceptsEditorTag is the regression test for the reported failure:
// a level.dat carrying '.editorLevelMigrationVersion' must open for the engine
// without losing the tag.
func TestLevelDatAcceptsEditorTag(t *testing.T) {
	dir := t.TempDir()
	path := buildLevelDat(t, dir, leveldat.Data{
		LevelName:      "Mundo do Samuel",
		StorageVersion: 10,
		GameType:       0,
		RandomSeed:     123456789,
	}, 7)

	// The engine cannot read it before the fix: this is the exact error the app
	// reported.
	_, payload, err := readLevelDat(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var probe leveldat.Data
	if err := nbt.UnmarshalEncoding(payload, &probe, nbt.LittleEndian); err == nil {
		t.Fatal("o level.dat de teste deveria falhar antes da correção")
	}

	fix, err := prepareLevelDat(path)
	if err != nil {
		t.Fatalf("prepareLevelDat: %v", err)
	}
	if fix == nil {
		t.Fatal("esperava uma correção para a tag extra")
	}
	if len(fix.Names) != 1 || fix.Names[0] != editorTag {
		t.Fatalf("tags preservadas inesperadas: %v", fix.Names)
	}
	if fix.LevelName != "Mundo do Samuel" {
		t.Fatalf("nome do nível perdido: %q", fix.LevelName)
	}

	// The engine now reads the file.
	_, payload, err = readLevelDat(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var data leveldat.Data
	if err := nbt.UnmarshalEncoding(payload, &data, nbt.LittleEndian); err != nil {
		t.Fatalf("o motor ainda não consegue ler o level.dat: %v", err)
	}
	if data.LevelName != "Mundo do Samuel" {
		t.Fatalf("nome do nível errado após a correção: %q", data.LevelName)
	}

	// A cópia do original fica ao lado.
	if _, err := os.Stat(path + levelDatBackupSuffix); err != nil {
		t.Fatalf("backup do level.dat ausente: %v", err)
	}

	// The engine saves the world (new name, new time) and drops the unknown tag.
	saved := data
	saved.LevelName = "Mundo salvo"
	saved.Time = 987654321
	var ld leveldat.LevelDat
	if err := ld.Marshal(&saved); err != nil {
		t.Fatalf("marshal salvo: %v", err)
	}
	buf := &fixedBuffer{}
	if err := ld.Write(buf); err != nil {
		t.Fatalf("write salvo: %v", err)
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatalf("gravar salvo: %v", err)
	}

	// Restore must put the tag back without touching the engine's data.
	if err := fix.restore(); err != nil {
		t.Fatalf("restore: %v", err)
	}
	_, payload, err = readLevelDat(path)
	if err != nil {
		t.Fatalf("read após restore: %v", err)
	}
	spans, _, err := nbtScan(payload)
	if err != nil {
		t.Fatalf("scan após restore: %v", err)
	}
	found := false
	for _, sig := range spans {
		if sig.Name == editorTag {
			found = true
			raw := payload[sig.Start:sig.End]
			if raw[0] != 0x03 {
				t.Fatalf("tipo da tag mudou: %d", raw[0])
			}
			nameLen := int(binary.LittleEndian.Uint16(raw[1:3]))
			value := raw[3+nameLen:]
			if len(value) != 4 || binary.LittleEndian.Uint32(value) != 7 {
				t.Fatalf("valor da tag perdido: %v", value)
			}
		}
	}
	if !found {
		t.Fatal("a tag não voltou ao level.dat depois de salvar")
	}
	var reopened leveldat.Data
	if err := nbt.UnmarshalEncoding(stripForEngine(t, payload), &reopened, nbt.LittleEndian); err != nil {
		t.Fatalf("reabrir depois de salvar: %v", err)
	}
	if reopened.LevelName != "Mundo salvo" || reopened.Time != 987654321 {
		t.Fatalf("dados do motor perdidos: nome=%q time=%d", reopened.LevelName, reopened.Time)
	}
}

// stripForEngine mimics what the next start does before handing the file to the
// engine (the tag is again unknown to it).
func stripForEngine(t *testing.T, payload []byte) []byte {
	t.Helper()
	spans, end, err := nbtScan(payload)
	if err != nil {
		t.Fatalf("scan: %v", err)
	}
	return stripTags(payload, map[string]bool{editorTag: true}, spans, end)
}

// TestLevelDatWithoutExtraTagsNeedsNoFix keeps the fast path honest.
func TestLevelDatWithoutExtraTagsNeedsNoFix(t *testing.T) {
	dir := t.TempDir()
	var ld leveldat.LevelDat
	if err := ld.Marshal(&leveldat.Data{LevelName: "limpo", StorageVersion: 10}); err != nil {
		t.Fatalf("marshal: %v", err)
	}
	path := filepath.Join(dir, "level.dat")
	if err := ld.WriteFile(path); err != nil {
		t.Fatalf("write: %v", err)
	}
	fix, err := prepareLevelDat(path)
	if err != nil {
		t.Fatalf("prepareLevelDat: %v", err)
	}
	if fix != nil {
		t.Fatalf("não deveria haver correção, veio %v", fix.Names)
	}
	if _, err := os.Stat(path + levelDatBackupSuffix); !os.IsNotExist(err) {
		t.Fatal("não deveria criar backup quando nada muda")
	}
}

// TestValidateWorldCodes covers the three distinct failures the app shows.
func TestValidateWorldCodes(t *testing.T) {
	t.Run("arquivo invalido", func(t *testing.T) {
		dir := t.TempDir()
		res := validateWorldDir(dir)
		if res.OK || res.Code != worldCodeInvalidFile {
			t.Fatalf("esperava %s, veio %+v", worldCodeInvalidFile, res)
		}
	})

	t.Run("level.dat truncado", func(t *testing.T) {
		dir := t.TempDir()
		if err := os.WriteFile(filepath.Join(dir, "level.dat"), []byte{1, 2, 3}, 0o644); err != nil {
			t.Fatal(err)
		}
		res := validateWorldDir(dir)
		if res.OK || res.Code != worldCodeInvalidFile {
			t.Fatalf("esperava %s, veio %+v", worldCodeInvalidFile, res)
		}
	})

	t.Run("importacao incompleta", func(t *testing.T) {
		dir := t.TempDir()
		buildLevelDat(t, dir, leveldat.Data{LevelName: "sem db", StorageVersion: 10}, 1)
		res := validateWorldDir(dir)
		if res.OK || res.Code != worldCodeIncomplete {
			t.Fatalf("esperava %s, veio %+v", worldCodeIncomplete, res)
		}
	})

	t.Run("db vazio", func(t *testing.T) {
		dir := t.TempDir()
		buildLevelDat(t, dir, leveldat.Data{LevelName: "db vazio", StorageVersion: 10}, 1)
		if err := os.MkdirAll(filepath.Join(dir, "db"), 0o755); err != nil {
			t.Fatal(err)
		}
		res := validateWorldDir(dir)
		if res.OK || res.Code != worldCodeIncomplete {
			t.Fatalf("esperava %s, veio %+v", worldCodeIncomplete, res)
		}
	})

	t.Run("mundo valido com tag extra", func(t *testing.T) {
		dir := t.TempDir()
		buildLevelDat(t, dir, leveldat.Data{LevelName: "completo", StorageVersion: 10}, 3)
		db := filepath.Join(dir, "db")
		if err := os.MkdirAll(db, 0o755); err != nil {
			t.Fatal(err)
		}
		for _, name := range []string{"CURRENT", "MANIFEST-000001", "000003.log"} {
			if err := os.WriteFile(filepath.Join(db, name), []byte("x"), 0o644); err != nil {
				t.Fatal(err)
			}
		}
		res := validateWorldDir(dir)
		if !res.OK {
			t.Fatalf("esperava mundo válido, veio %+v", res)
		}
		if len(res.FixedTags) != 1 || res.FixedTags[0] != editorTag {
			t.Fatalf("tags corrigidas inesperadas: %v", res.FixedTags)
		}
		if res.LevelName != "completo" {
			t.Fatalf("nome do nível errado: %q", res.LevelName)
		}
		if res.DbFiles != 3 {
			t.Fatalf("arquivos de db/ contados errado: %d", res.DbFiles)
		}
	})
}

// TestNbtSkipRejectsTruncated ensures corrupt data is reported, not panicked on.
func TestNbtSkipRejectsTruncated(t *testing.T) {
	payload := []byte{0x0A, 0x00, 0x00, 0x08, 0x00, 0x01, 'a', 0x00, 0x10, 'x'}
	if _, _, err := nbtScan(payload); err == nil {
		t.Fatal("esperava erro para string truncada")
	}
}
