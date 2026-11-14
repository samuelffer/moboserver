package main

// Política de geração por mundo.
//
// O servidor embutido sempre gerou terreno com o gerador simplificado deste
// projeto. Como agora isso fica registrado, um mundo não muda de motor sem que
// alguém veja o aviso: o arquivo `generation.json` guarda qual gerador criou os
// chunks novos daquele mundo. Se o registro existente divergir da configuração
// atual, o servidor apenas avisa — nunca troca o gerador nem apaga chunks.

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// generationEngine identifica o gerador usado para chunks novos.
const generationEngine = "moboserver-terrain-v1"

type generationPolicy struct {
	// Engine é o identificador do gerador que criou chunks novos.
	Engine string `json:"engine"`
	// Overworld diz qual gerador vale para o Overworld ("terrain" ou "flat").
	Overworld string `json:"overworld"`
	// Imported indica que o mundo veio de um .mcworld.
	Imported bool `json:"imported"`
	// EngineVersion é a versão do aplicativo que gravou o registro.
	EngineVersion string `json:"engine_version"`
}

// loadGenerationPolicy lê o registro; ausente não é erro.
func loadGenerationPolicy(path string) (generationPolicy, bool, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return generationPolicy{}, false, nil
		}
		return generationPolicy{}, false, err
	}
	var p generationPolicy
	if err := json.Unmarshal(data, &p); err != nil {
		return generationPolicy{}, false, fmt.Errorf("registro de geração inválido: %w", err)
	}
	return p, true, nil
}

// recordGenerationPolicy grava o registro quando ele não existe. Devolve o
// registro efetivo e se houve divergência em relação ao pedido.
func recordGenerationPolicy(path string, wanted generationPolicy) (generationPolicy, bool, error) {
	existing, found, err := loadGenerationPolicy(path)
	if err != nil {
		return generationPolicy{}, false, err
	}
	if found {
		changed := existing.Engine != wanted.Engine || existing.Overworld != wanted.Overworld
		return existing, changed, nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return generationPolicy{}, false, err
	}
	data, err := json.MarshalIndent(wanted, "", "  ")
	if err != nil {
		return generationPolicy{}, false, err
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return generationPolicy{}, false, err
	}
	return wanted, false, nil
}

// generationPath devolve onde o registro fica: na pasta de dados do aplicativo,
// nunca dentro do mundo (o provider do mundo é do Minecraft).
func generationPath(dataDir string) string {
	return filepath.Join(dataDir, "generation.json")
}
