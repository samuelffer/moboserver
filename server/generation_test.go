package main

import (
	"os"
	"path/filepath"
	"testing"
)

// O registro de geração não pode trocar de motor silenciosamente.
func TestGenerationPolicyNaoTrocaEmSilencio(t *testing.T) {
	dir := t.TempDir()
	path := generationPath(dir)

	first := generationPolicy{Engine: generationEngine, Overworld: "terrain", Imported: true, EngineVersion: "1.4.3"}
	got, changed, err := recordGenerationPolicy(path, first)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Fatal("o primeiro registro não pode ser reportado como divergência")
	}
	if got.Engine != generationEngine || got.Overworld != "terrain" {
		t.Fatalf("registro inesperado: %+v", got)
	}
	if !got.Imported {
		t.Fatal("a origem importada precisa ser preservada")
	}

	// Pedir outro gerador para o mesmo mundo: o registro antigo permanece e a
	// divergência é informada.
	other := generationPolicy{Engine: generationEngine, Overworld: "flat", EngineVersion: "1.4.3"}
	got, changed, err = recordGenerationPolicy(path, other)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("esperava divergência ao pedir outro gerador")
	}
	if got.Overworld != "terrain" {
		t.Fatalf("o registro foi alterado sem aviso: %+v", got)
	}

	// E continua legível depois de reabrir.
	reloaded, found, err := loadGenerationPolicy(path)
	if err != nil || !found {
		t.Fatalf("registro não recarregou: %v", err)
	}
	if reloaded.Overworld != "terrain" {
		t.Fatalf("registro recarregado = %+v", reloaded)
	}
}

func TestGenerationPolicyInexistenteNaoEerro(t *testing.T) {
	dir := t.TempDir()
	_, found, err := loadGenerationPolicy(generationPath(dir))
	if err != nil {
		t.Fatalf("registro ausente virou erro: %v", err)
	}
	if found {
		t.Fatal("registro não deveria existir")
	}
	if _, err := os.Stat(dir); err != nil {
		t.Fatal(err)
	}
}

func TestGenerationPolicyCorrompidoAvisa(t *testing.T) {
	dir := t.TempDir()
	path := generationPath(dir)
	if err := os.WriteFile(path, []byte("{isso nao e json"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := loadGenerationPolicy(path); err == nil {
		t.Fatal("JSON inválido deveria ser reportado")
	}
	// O arquivo original não é apagado por causa do erro.
	if _, err := os.Stat(filepath.Clean(path)); err != nil {
		t.Fatal("o registro foi removido")
	}
}
