package main

// Leitura compativel de level.dat importados do Minecraft Bedrock.
//
// O motor (dragonfly v0.11.6 -> server/world/mcdb/leveldat) decodifica o NBT do
// level.dat em uma struct fechada (leveldat.Data) usando
// gophertunnel/minecraft/nbt, que devolve
//
//	nbt: unexpected named tag '.editorLevelMigrationVersion' with type 10 at
//	offset ...: not present in struct to be decoded into
//
// para qualquer tag que a struct nao conheca. Versoes novas do jogo escrevem
// tags extras ('.editorLevelMigrationVersion', 'editorWorldType', ...), entao o
// mundo inteiro deixa de abrir por causa de um metadado irrelevante.
//
// A correcao nao remove a tag: ela e recortada do NBT por deslocamento de bytes
// (preservando tipo e valor exatos), o level.dat original e guardado ao lado, e
// as tags voltam ao arquivo quando o mundo e salvo. Assim o motor abre o mundo e
// nada do metadado se perde. Quando o problema e um tipo incompativel (e nao uma
// tag desconhecida), o erro e reportado como "metadados incompativeis" em vez de
// ser mascarado.

import (
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/df-mc/dragonfly/server/world/mcdb/leveldat"
	"github.com/sandertv/gophertunnel/minecraft/nbt"
)

// Códigos de erro usados pelo app para mostrar mensagens distintas.
const (
	worldCodeInvalidFile     = "invalid_file"
	worldCodeIncomplete      = "incomplete_import"
	worldCodeIncompatible    = "incompatible_metadata"
	levelDatBackupSuffix     = ".original"
	maxLevelDatPayloadLength = 64 << 20
)

// worldError carries the code the Android app maps to a message.
type worldError struct {
	Code   string
	Detail string
}

func (e *worldError) Error() string { return e.Detail }

func worldErr(code, format string, args ...any) error {
	return &worldError{Code: code, Detail: fmt.Sprintf(format, args...)}
}

// nbtSig is a top-level tag inside the root compound.
type nbtSig struct {
	Name  string
	Type  byte
	Start int // offset of the type byte, relative to the payload
	End   int // offset right after the payload
}

// nbtPayload validates the root compound of a level.dat payload and returns the
// offset where its tags start.
func nbtPayload(p []byte) (int, error) {
	if len(p) < 3 {
		return 0, worldErr(worldCodeInvalidFile, "level.dat truncado: NBT com %d byte(s)", len(p))
	}
	if p[0] != 0x0A {
		return 0, worldErr(worldCodeInvalidFile, "level.dat nao e um compound NBT (tipo %d na raiz)", p[0])
	}
	nameLen := int(binary.LittleEndian.Uint16(p[1:3]))
	if len(p) < 3+nameLen {
		return 0, worldErr(worldCodeInvalidFile, "level.dat truncado no nome da raiz")
	}
	return 3 + nameLen, nil
}

// nbtSkip returns the offset right after the payload of the tag at off.
func nbtSkip(p []byte, off int, typ byte, depth int) (int, error) {
	if depth > 64 {
		return 0, worldErr(worldCodeInvalidFile, "level.dat com aninhamento NBT profundo demais")
	}
	need := func(n int) error {
		if off+n > len(p) {
			return worldErr(worldCodeInvalidFile, "level.dat truncado: faltam %d byte(s) no NBT", off+n-len(p))
		}
		return nil
	}
	switch typ {
	case 0x01: // TAG_Byte
		if err := need(1); err != nil {
			return 0, err
		}
		return off + 1, nil
	case 0x02: // TAG_Short
		if err := need(2); err != nil {
			return 0, err
		}
		return off + 2, nil
	case 0x03, 0x05: // TAG_Int, TAG_Float
		if err := need(4); err != nil {
			return 0, err
		}
		return off + 4, nil
	case 0x04, 0x06: // TAG_Long, TAG_Double
		if err := need(8); err != nil {
			return 0, err
		}
		return off + 8, nil
	case 0x07, 0x0B, 0x0C: // TAG_ByteArray, TAG_IntArray, TAG_LongArray
		if err := need(4); err != nil {
			return 0, err
		}
		n := int(int32(binary.LittleEndian.Uint32(p[off : off+4])))
		if n < 0 {
			return 0, worldErr(worldCodeInvalidFile, "level.dat com array NBT de tamanho negativo")
		}
		size := n
		switch typ {
		case 0x0B:
			size = n * 4
		case 0x0C:
			size = n * 8
		}
		if err := need(4 + size); err != nil {
			return 0, err
		}
		return off + 4 + size, nil
	case 0x08: // TAG_String
		if err := need(2); err != nil {
			return 0, err
		}
		n := int(binary.LittleEndian.Uint16(p[off : off+2]))
		if err := need(2 + n); err != nil {
			return 0, err
		}
		return off + 2 + n, nil
	case 0x09: // TAG_List
		if err := need(5); err != nil {
			return 0, err
		}
		elem := p[off]
		n := int(int32(binary.LittleEndian.Uint32(p[off+1 : off+5])))
		if n < 0 {
			return 0, worldErr(worldCodeInvalidFile, "level.dat com lista NBT de tamanho negativo")
		}
		cursor := off + 5
		for i := 0; i < n; i++ {
			next, err := nbtSkip(p, cursor, elem, depth+1)
			if err != nil {
				return 0, err
			}
			cursor = next
		}
		return cursor, nil
	case 0x0A: // TAG_Compound
		cursor := off
		for {
			if cursor >= len(p) {
				return 0, worldErr(worldCodeInvalidFile, "level.dat truncado dentro de um compound")
			}
			t := p[cursor]
			cursor++
			if t == 0x00 {
				return cursor, nil
			}
			if cursor+2 > len(p) {
				return 0, worldErr(worldCodeInvalidFile, "level.dat truncado no nome de um compound")
			}
			nameLen := int(binary.LittleEndian.Uint16(p[cursor : cursor+2]))
			cursor += 2 + nameLen
			if cursor > len(p) {
				return 0, worldErr(worldCodeInvalidFile, "level.dat truncado no nome de um compound")
			}
			next, err := nbtSkip(p, cursor, t, depth+1)
			if err != nil {
				return 0, err
			}
			cursor = next
		}
	default:
		return 0, worldErr(worldCodeInvalidFile, "level.dat com tag NBT desconhecida (tipo %d)", typ)
	}
}

// nbtScan lists the top-level tags of the root compound. It also returns the
// offset of the TAG_End that closes the root compound.
func nbtScan(p []byte) ([]nbtSig, int, error) {
	body, err := nbtPayload(p)
	if err != nil {
		return nil, 0, err
	}
	var out []nbtSig
	cursor := body
	for {
		if cursor >= len(p) {
			return nil, 0, worldErr(worldCodeInvalidFile, "level.dat sem TAG_End na raiz")
		}
		typ := p[cursor]
		if typ == 0x00 {
			return out, cursor, nil
		}
		if cursor+3 > len(p) {
			return nil, 0, worldErr(worldCodeInvalidFile, "level.dat truncado em um nome de tag")
		}
		nameLen := int(binary.LittleEndian.Uint16(p[cursor+1 : cursor+3]))
		if cursor+3+nameLen > len(p) {
			return nil, 0, worldErr(worldCodeInvalidFile, "level.dat truncado em um nome de tag")
		}
		name := string(p[cursor+3 : cursor+3+nameLen])
		start := cursor
		end, err := nbtSkip(p, cursor+3+nameLen, typ, 0)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, nbtSig{Name: name, Type: typ, Start: start, End: end})
		cursor = end
	}
}

// levelDatFix remembers what was taken out so it can be put back on save.
type levelDatFix struct {
	Path      string
	Removed   [][]byte
	Names     []string
	LevelName string
	restored  bool
}

// readLevelDat splits a level.dat into storage version and NBT payload.
func readLevelDat(path string) (int32, []byte, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil, worldErr(worldCodeInvalidFile, "o mundo não tem level.dat")
		}
		return 0, nil, worldErr(worldCodeInvalidFile, "não foi possível ler o level.dat: %v", err)
	}
	if len(raw) < 8 {
		return 0, nil, worldErr(worldCodeInvalidFile, "level.dat tem apenas %d byte(s)", len(raw))
	}
	version := int32(binary.LittleEndian.Uint32(raw[0:4]))
	length := int32(binary.LittleEndian.Uint32(raw[4:8]))
	payload := raw[8:]
	if length < 0 {
		return 0, nil, worldErr(worldCodeInvalidFile, "level.dat com tamanho de NBT inválido (%d)", length)
	}
	if int(length) > len(payload) {
		return 0, nil, worldErr(worldCodeInvalidFile,
			"level.dat está cortado: o cabeçalho pede %d byte(s) e o arquivo tem %d", length, len(payload))
	}
	payload = payload[:length]
	if len(payload) > maxLevelDatPayloadLength {
		return 0, nil, worldErr(worldCodeInvalidFile, "level.dat grande demais (%d bytes)", len(payload))
	}
	return version, payload, nil
}

// writeLevelDat writes version + payload with a correct header.
func writeLevelDat(path string, version int32, payload []byte) error {
	buf := make([]byte, 8+len(payload))
	binary.LittleEndian.PutUint32(buf[0:4], uint32(version))
	binary.LittleEndian.PutUint32(buf[4:8], uint32(len(payload)))
	copy(buf[8:], payload)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, buf, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// stripTags rebuilds the payload without the named top-level tags.
func stripTags(payload []byte, drop map[string]bool, spans []nbtSig, end int) []byte {
	body, err := nbtPayload(payload)
	if err != nil {
		return payload
	}
	kept := make([]byte, 0, len(payload))
	kept = append(kept, payload[:body]...)
	for _, sig := range spans {
		if drop[sig.Name] {
			continue
		}
		kept = append(kept, payload[sig.Start:sig.End]...)
	}
	kept = append(kept, payload[end:]...)
	if len(kept) > maxLevelDatPayloadLength {
		return payload
	}
	return kept
}

// prepareLevelDat makes a level.dat readable by the engine without losing
// metadata. It returns nil when the file needs no change.
func prepareLevelDat(path string) (*levelDatFix, error) {
	version, payload, err := readLevelDat(path)
	if err != nil {
		return nil, err
	}
	// Fast path: the engine reads this file as it is.
	var probe leveldat.Data
	if err := nbt.UnmarshalEncoding(payload, &probe, nbt.LittleEndian); err == nil {
		return nil, nil
	}

	spans, end, err := nbtScan(payload)
	if err != nil {
		return nil, err
	}
	available := make(map[string]bool, len(spans))
	for _, sig := range spans {
		available[sig.Name] = true
	}

	drop := map[string]bool{}
	var decoded leveldat.Data
	var lastErr error
	for attempt := 0; attempt < 64; attempt++ {
		candidate := stripTags(payload, drop, spans, end)
		var data leveldat.Data
		err := nbt.UnmarshalEncoding(candidate, &data, nbt.LittleEndian)
		if err == nil {
			decoded = data
			break
		}
		lastErr = err
		var unexpected nbt.UnexpectedNamedTagError
		if errors.As(err, &unexpected) && unexpected.TagName != "" && available[unexpected.TagName] && !drop[unexpected.TagName] {
			// A tag the engine cannot store, but that we can keep intact and put
			// back when the world is saved.
			drop[unexpected.TagName] = true
			continue
		}
		// Anything else (type mismatch, corrupt NBT, unknown nested tag) is a
		// real incompatibility: report it instead of dropping data silently.
		return nil, worldErr(worldCodeIncompatible,
			"o level.dat deste mundo tem metadados que esta versão do motor não lê (%v). "+
				"Abra e salve o mundo no Minecraft uma vez, ou importe um .mcworld exportado por ele", err)
	}
	if len(drop) == 0 {
		return nil, worldErr(worldCodeIncompatible,
			"o level.dat deste mundo não pôde ser lido pelo motor: %v", lastErr)
	}

	fix := &levelDatFix{Path: path, LevelName: decoded.LevelName}
	names := make([]string, 0, len(drop))
	for name := range drop {
		names = append(names, name)
	}
	sort.Strings(names)
	fix.Names = names
	for _, sig := range spans {
		if drop[sig.Name] {
			raw := make([]byte, sig.End-sig.Start)
			copy(raw, payload[sig.Start:sig.End])
			fix.Removed = append(fix.Removed, raw)
		}
	}

	backup := path + levelDatBackupSuffix
	if _, statErr := os.Stat(backup); os.IsNotExist(statErr) {
		original, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil, worldErr(worldCodeInvalidFile, "não foi possível guardar uma cópia do level.dat: %v", readErr)
		}
		if writeErr := os.WriteFile(backup, original, 0o644); writeErr != nil {
			return nil, worldErr(worldCodeInvalidFile, "não foi possível guardar uma cópia do level.dat: %v", writeErr)
		}
	}
	if err := writeLevelDat(path, version, stripTags(payload, drop, spans, end)); err != nil {
		return nil, worldErr(worldCodeInvalidFile, "não foi possível ajustar o level.dat: %v", err)
	}
	return fix, nil
}

// restore puts the preserved tags back into the level.dat the engine wrote.
func (f *levelDatFix) restore() error {
	if f == nil || f.restored || len(f.Removed) == 0 {
		return nil
	}
	version, payload, err := readLevelDat(f.Path)
	if err != nil {
		return err
	}
	spans, end, err := nbtScan(payload)
	if err != nil {
		return err
	}
	present := make(map[string]bool, len(spans))
	for _, sig := range spans {
		present[sig.Name] = true
	}
	var add []byte
	added := make([]string, 0, len(f.Names))
	for i, name := range f.Names {
		if present[name] {
			continue
		}
		add = append(add, f.Removed[i]...)
		added = append(added, name)
	}
	if len(add) == 0 {
		f.restored = true
		return nil
	}
	merged := make([]byte, 0, len(payload)+len(add))
	merged = append(merged, payload[:end]...)
	merged = append(merged, add...)
	merged = append(merged, payload[end:]...)
	if err := writeLevelDat(f.Path, version, merged); err != nil {
		return err
	}
	f.restored = true
	f.Names = added
	return nil
}

// worldValidation is the JSON document the app reads after importing a world.
type worldValidation struct {
	OK             bool     `json:"ok"`
	Code           string   `json:"code,omitempty"`
	Detail         string   `json:"detail,omitempty"`
	LevelName      string   `json:"levelName,omitempty"`
	StorageVersion int      `json:"storageVersion,omitempty"`
	FixedTags      []string `json:"fixedTags,omitempty"`
	DbFiles        int      `json:"dbFiles"`
	WorldBytes     int64    `json:"worldBytes"`
}

// validateWorldDir checks a world folder before the app offers it to the player.
// It validates the .mcworld structure (level.dat + db/) and makes the level.dat
// readable by the engine, keeping a copy of the original.
func validateWorldDir(dir string) worldValidation {
	res := worldValidation{}
	st, err := os.Stat(dir)
	if err != nil || !st.IsDir() {
		res.Code = worldCodeInvalidFile
		res.Detail = "a pasta do mundo não existe"
		return res
	}
	levelPath := filepath.Join(dir, "level.dat")
	version, _, err := readLevelDat(levelPath)
	if err != nil {
		res.Code = worldCodeInvalidFile
		res.Detail = err.Error()
		return res
	}
	res.StorageVersion = int(version)

	// db/ is where the chunks and entities live: without it the world is only a
	// header and the game would open an empty map.
	db := filepath.Join(dir, "db")
	count, size, dbErr := countDbFiles(db)
	if dbErr != nil {
		res.Code = worldCodeIncomplete
		res.Detail = dbErr.Error()
		return res
	}
	if count == 0 {
		res.Code = worldCodeIncomplete
		res.Detail = "a pasta db/ do mundo está vazia: a importação não trouxe os dados do mundo"
		return res
	}
	res.DbFiles, res.WorldBytes = count, size

	fix, err := prepareLevelDat(levelPath)
	if err != nil {
		var we *worldError
		if errors.As(err, &we) {
			res.Code = we.Code
		} else {
			res.Code = worldCodeInvalidFile
		}
		res.Detail = err.Error()
		return res
	}
	if fix != nil {
		res.FixedTags = fix.Names
		if fix.LevelName != "" {
			res.LevelName = strings.TrimSpace(fix.LevelName)
		}
	}
	if res.LevelName == "" {
		var data leveldat.Data
		if _, payload, err := readLevelDat(levelPath); err == nil {
			if err := nbt.UnmarshalEncoding(payload, &data, nbt.LittleEndian); err == nil {
				res.LevelName = strings.TrimSpace(data.LevelName)
			}
		}
	}
	res.OK = true
	return res
}

// countDbFiles counts the files inside db/ and their total size.
func countDbFiles(dir string) (int, int64, error) {
	st, err := os.Stat(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, 0, fmt.Errorf("o mundo não tem a pasta db/ (dados de chunks e entidades)")
		}
		return 0, 0, err
	}
	if !st.IsDir() {
		return 0, 0, fmt.Errorf("db/ não é uma pasta")
	}
	var count int
	var size int64
	err = filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		count++
		size += info.Size()
		return nil
	})
	return count, size, err
}

// levelDatLooksComplete is a cheap check used by the self test.
func levelDatLooksComplete(path string) bool {
	version, payload, err := readLevelDat(path)
	if err != nil || version <= 0 {
		return false
	}
	_, _, err = nbtScan(payload)
	return err == nil
}
