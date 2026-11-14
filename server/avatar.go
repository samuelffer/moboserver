package main

// Miniaturas de cabeca a partir da skin real da sessao Bedrock.
//
// A skin chega em player.Skin() (skin.Skin): Pix em RGBA direto (nao
// pre-multiplicado), Bounds() com as dimensoes e Model/ModelConfig/Persona
// descrevendo a geometria.
//
// A geometria NAO e adivinhada por texto. Quando o cliente envia um modelo
// (skin.Model), o JSON e interpretado: bonecos, cubos, UV e inflate. O cubo da
// cabeca serve de fonte para o rosto (UV + profundidade do cubo) e o cubo com
// inflate > 0 (chapeu/camada externa) para a sobreposicao. Se o modelo nao traz
// a cabeca, so o identificador humanoid padrao autoriza usar as UVs fixas
// conhecidas:
//
//	64x64  e 64x32 (classico): rosto [8,16)x[8,16), camada externa [40,48)x[8,16)
//	128x128 (layout padrão em escala dupla): rosto [16,32)x[16,32),
//	                                        camada externa [80,96)x[16,32)
//
// A camada externa e composta com "source over", a miniatura e ampliada com
// nearest-neighbor (pixels definidos) e codificada em PNG. Persona e geometrias
// que nao permitem localizar a cabeca devolvem "indisponivel" com motivo
// especifico, em vez de uma imagem errada.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"math"
	"strings"

	"github.com/df-mc/dragonfly/server/player/skin"
)

const avatarSize = 64

// Limites de complexidade: um modelo hostil ou gigante nao pode travar a
// sessao nem consumir memoria sem controle.
const (
	maxAvatarModelBytes = 96 * 1024
	maxAvatarBones      = 512
	maxAvatarCubes      = 4096
)

// avatarResult is what the app receives for one player.
type avatarResult struct {
	Available bool
	PNG       []byte
	Hash      string
	Layout    string
	Reason    string
	Info      avatarDiagnostics
}

// avatarDiagnostics descreve o que a biblioteca entregou e o que foi feito.
// Nao carrega PNG, base64 nem a skin inteira: so contagens e nomes.
type avatarDiagnostics struct {
	Persona    bool   `json:"persona"`
	TexWidth   int    `json:"tex_w"`
	TexHeight  int    `json:"tex_h"`
	PixelBytes int    `json:"pix_bytes"`
	ModelBytes int    `json:"model_bytes"`
	Geometry   string `json:"geometry"`
	Layout     string `json:"layout"`
	HeadCube   bool   `json:"head_cube"`
	Rendered   bool   `json:"rendered"`
	Reason     string `json:"reason"`
}

// avatarRect é um retângulo em pixels da textura.
type avatarRect struct {
	x, y, size int
}

func (r avatarRect) inside(w, h int) bool {
	return r.size > 0 && r.x >= 0 && r.y >= 0 && r.x+r.size <= w && r.y+r.size <= h
}

type avatarPlan struct {
	face    avatarRect
	hat     avatarRect
	hasHat  bool
	layout  string
	legacy  bool
}

// ------------------------------------------------------------------ geometria

type geoDocument struct {
	FormatVersion string        `json:"format_version"`
	Geometry      []geoGeometry `json:"minecraft:geometry"`
}

type geoGeometry struct {
	Description geoDescription `json:"description"`
	Bones       []geoBone      `json:"bones"`
}

type geoDescription struct {
	Identifier     string  `json:"identifier"`
	TextureWidth   float64 `json:"texture_width"`
	TextureHeight  float64 `json:"texture_height"`
	VisibleBoundsW float64 `json:"visible_bounds_width"`
	VisibleBoundsH float64 `json:"visible_bounds_height"`
}

type geoBone struct {
	Name   string    `json:"name"`
	Parent string    `json:"parent"`
	Pivot  []float64 `json:"pivot"`
	Cubes  []geoCube `json:"cubes"`
}

type geoCube struct {
	Origin  []float64 `json:"origin"`
	Size    []float64 `json:"size"`
	UV      []float64 `json:"uv"`
	Inflate float64   `json:"inflate"`
}

// ---------------------------------------------------------------------- plano

// planAvatar decide, a partir da geometria que realmente chegou, quais pixels
// formam o rosto e a camada externa. Toda rejeicao traz um motivo concreto.
func planAvatar(s skin.Skin) (avatarPlan, avatarDiagnostics, string) {
	bounds := s.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	info := avatarDiagnostics{
		Persona:    s.Persona,
		TexWidth:   w,
		TexHeight:  h,
		PixelBytes: len(s.Pix),
		ModelBytes: len(s.Model),
	}

	// Limites de tamanho antes de alocar ou indexar qualquer coisa.
	if w <= 0 || h <= 0 || w > 256 || h > 256 {
		return avatarPlan{}, info, "dimensões de skin inesperadas"
	}
	if len(s.Pix) != w*h*4 {
		return avatarPlan{}, info, fmt.Sprintf(
			"textura com tamanho inconsistente: %d byte(s) para %dx%d (esperado %d)",
			len(s.Pix), w, h, w*h*4,
		)
	}

	model, modelErr := decodeGeometry(s.Model)
	if modelErr != "" {
		return avatarPlan{}, info, modelErr
	}
	geometry, geometryReason := selectGeometry(model, s.ModelConfig.Default)
	info.Geometry = geometry.Description.Identifier

	// 1) Caminho preferido: o modelo traz o cubo da cabeca com UV.
	if geometry.Bones != nil {
		if plan, ok := planFromHeadCube(s, geometry); ok {
			info.HeadCube = true
			info.Layout = plan.layout
			return plan, info, ""
		}
		if s.Persona {
			return avatarPlan{}, info, "skin Persona: o modelo chegou, mas não há cubo de cabeça com UV dentro da textura " +
				"para recortar com segurança"
		}
	}

	// 2) Persona sem cabeca localizavel: nao recortar coordenadas fixas.
	if s.Persona {
		if len(s.Model) == 0 {
			return avatarPlan{}, info, "skin Persona: o cliente não envia a geometria e a biblioteca não expõe " +
				"os cubos da cabeça, então não é possível recortar a textura sem inventar coordenadas"
		}
		return avatarPlan{}, info, "skin Persona: " + fallbackReason(geometryReason)
	}

	// 3) Modelo vazio ou humanoid padrao sem cubos: UVs fixas conhecidas.
	if len(s.Model) == 0 || strings.HasPrefix(strings.ToLower(geometry.Description.Identifier), "geometry.humanoid") {
		plan, ok := standardPlan(w, h)
		if !ok {
			return avatarPlan{}, info, fmt.Sprintf(
				"textura %dx%d fora dos layouts humanoides conhecidos (64x64, 64x32, 128x128)",
				w, h,
			)
		}
		info.Layout = plan.layout
		return plan, info, ""
	}

	if geometryReason != "" {
		return avatarPlan{}, info, geometryReason
	}
	return avatarPlan{}, info, "geometria personalizada sem cubo de cabeça com UV: não há como localizar o rosto " +
		"na textura sem adivinhar"
}

func fallbackReason(reason string) string {
	if reason == "" {
		return "geometria sem cabeça localizável"
	}
	return reason
}

// decodeGeometry devolve o documento decodificado ou um motivo de rejeicao.
func decodeGeometry(raw []byte) (geoDocument, string) {
	if len(raw) == 0 {
		return geoDocument{}, ""
	}
	if len(raw) > maxAvatarModelBytes {
		return geoDocument{}, fmt.Sprintf("modelo de geometria grande demais (%d bytes)", len(raw))
	}
	var doc geoDocument
	if err := json.Unmarshal(raw, &doc); err != nil {
		return geoDocument{}, "geometria enviada pelo cliente não é JSON válido"
	}
	bones := 0
	for _, g := range doc.Geometry {
		bones += len(g.Bones)
	}
	if bones > maxAvatarBones {
		return geoDocument{}, fmt.Sprintf("geometria com bonecos demais (%d)", bones)
	}
	cubes := 0
	for _, g := range doc.Geometry {
		for _, b := range g.Bones {
			cubes += len(b.Cubes)
		}
	}
	if cubes > maxAvatarCubes {
		return geoDocument{}, fmt.Sprintf("geometria com cubos demais (%d)", cubes)
	}
	return doc, ""
}

// selectGeometry resolve qual geometria do documento é a usada pela skin,
// seguindo skin.ModelConfig.Default (o "default" escolhido pelo cliente).
func selectGeometry(doc geoDocument, defaultName string) (geoGeometry, string) {
	if len(doc.Geometry) == 0 {
		return geoGeometry{}, ""
	}
	normalized := normalizeGeometryID(defaultName)
	if normalized != "" {
		for _, g := range doc.Geometry {
			if normalizeGeometryID(g.Description.Identifier) == normalized {
				return g, ""
			}
		}
		return geoGeometry{}, fmt.Sprintf(
			"geometria \"%s\" indicada pela skin não veio no modelo", defaultName,
		)
	}
	if len(doc.Geometry) == 1 {
		return doc.Geometry[0], ""
	}
	// Sem indicação: preferir humanoid, que é o que o jogo usa por padrão.
	for _, g := range doc.Geometry {
		if strings.HasPrefix(strings.ToLower(g.Description.Identifier), "geometry.humanoid") {
			return g, ""
		}
	}
	return doc.Geometry[0], ""
}

// normalizeGeometryID removes the suffix the game adds for texture variants
// ("geometry.humanoid.customSlim" vs "geometry.humanoid.custom_slim").
func normalizeGeometryID(id string) string {
	id = strings.ToLower(strings.TrimSpace(id))
	id = strings.ReplaceAll(id, "_", "")
	id = strings.ReplaceAll(id, ".", "")
	return id
}

// planFromHeadCube monta o plano a partir do cubo da cabeça de um boneco
// "head" (ou equivalente). Sem esse cubo, devolve false.
func planFromHeadCube(s skin.Skin, geometry geoGeometry) (avatarPlan, bool) {
	w, h := s.Bounds().Dx(), s.Bounds().Dy()
	scale := geometryScale(geometry, w)
	head, ok := headBone(geometry)
	if !ok || len(head.Cubes) == 0 {
		return avatarPlan{}, false
	}

	// O cubo com inflate 0 desenha a cabeça; o inflate > 0 é a camada externa.
	var base, overlay *geoCube
	for i := range head.Cubes {
		cube := &head.Cubes[i]
		if cube.Inflate > 0 && overlay == nil {
			overlay = cube
			continue
		}
		if base == nil {
			base = cube
		}
	}
	if base == nil {
		return avatarPlan{}, false
	}

	face, ok := frontFaceOf(*base, scale)
	if !ok || !face.inside(w, h) {
		return avatarPlan{}, false
	}
	plan := avatarPlan{
		face:   face,
		layout: fmt.Sprintf("%s %dx%d", shortGeometryID(geometry.Description.Identifier), w, h),
	}
	if overlay != nil {
		if hat, ok := frontFaceOf(*overlay, scale); ok && hat.inside(w, h) {
			plan.hat = hat
			plan.hasHat = true
		}
	}
	return plan, true
}

// geometryScale converte as coordenadas do modelo para pixels da textura.
func geometryScale(g geoGeometry, texWidth int) float64 {
	declared := g.Description.TextureWidth
	if declared > 0 {
		return float64(texWidth) / declared
	}
	// Geometria no espaço 64x64 (padrão Bedrock).
	return float64(texWidth) / 64
}

// frontFaceOf usa o mapeamento box UV do Bedrock: a partir do canto (u,v) do
// cubo, a face frontal começa depois da profundidade (size[2]) nos dois eixos.
func frontFaceOf(cube geoCube, scale float64) (avatarRect, bool) {
	if len(cube.UV) < 2 || len(cube.Size) < 3 {
		return avatarRect{}, false
	}
	width := math.Abs(cube.Size[0])
	height := math.Abs(cube.Size[1])
	depth := math.Abs(cube.Size[2])
	if width < 1 || height < 1 || math.Abs(width-height) > 0.001 {
		return avatarRect{}, false
	}
	x := int(math.Round((cube.UV[0] + depth) * scale))
	y := int(math.Round((cube.UV[1] + depth) * scale))
	size := int(math.Round(width * scale))
	return avatarRect{x: x, y: y, size: size}, size > 0
}

func headBone(geometry geoGeometry) (geoBone, bool) {
	for _, bone := range geometry.Bones {
		name := strings.ToLower(strings.TrimSpace(bone.Name))
		if name == "head" || strings.HasSuffix(name, ".head") || strings.HasSuffix(name, "_head") {
			return bone, true
		}
	}
	return geoBone{}, false
}

func shortGeometryID(id string) string {
	trimmed := strings.TrimSpace(id)
	if trimmed == "" {
		return "geometria"
	}
	return trimmed
}

// standardPlan devolve as UVs fixas dos tres layouts humanoides.
func standardPlan(w, h int) (avatarPlan, bool) {
	switch {
	case w == 64 && h == 64:
		return avatarPlan{
			face:   avatarRect{x: 8, y: 8, size: 8},
			hat:    avatarRect{x: 40, y: 8, size: 8},
			hasHat: true,
			layout: "64x64",
		}, true
	case w == 64 && h == 32:
		return avatarPlan{
			face:   avatarRect{x: 8, y: 8, size: 8},
			hat:    avatarRect{x: 40, y: 8, size: 8},
			hasHat: true,
			layout: "64x32",
			legacy: true,
		}, true
	case w == 128 && h == 128:
		return avatarPlan{
			face:   avatarRect{x: 16, y: 16, size: 16},
			hat:    avatarRect{x: 80, y: 16, size: 16},
			hasHat: true,
			layout: "128x128",
		}, true
	}
	return avatarPlan{}, false
}

// ------------------------------------------------------------------ render

// renderAvatar crops the face, composes the outer layer and returns a 64x64 PNG.
func renderAvatar(s skin.Skin) avatarResult {
	plan, info, reason := planAvatar(s)
	info.Rendered = false
	if reason != "" {
		info.Reason = reason
		return avatarResult{Reason: reason, Info: info}
	}

	face := make([]color.RGBA, plan.face.size*plan.face.size)
	for y := 0; y < plan.face.size; y++ {
		for x := 0; x < plan.face.size; x++ {
			face[y*plan.face.size+x] = pixelAt(s, plan.face.x+x, plan.face.y+y)
		}
	}

	// Camada externa (chapéu/cabelo). Skins antigas 64x32 às vezes carregam alfa
	// opaco numa área que o jogo nunca desenhou; nesse caso o formato compatível
	// é ignorar a camada em vez de pintar um bloco preto sobre o rosto.
	useHat := plan.hasHat
	if useHat && plan.legacy && uniformOpaqueBlack(s, plan.hat.x, plan.hat.y, plan.hat.size) {
		useHat = false
	}
	out := image.NewRGBA(image.Rect(0, 0, avatarSize, avatarSize))
	for y := 0; y < avatarSize; y++ {
		for x := 0; x < avatarSize; x++ {
			// nearest-neighbor: cada pixel da miniatura aponta para um pixel da
			// textura (a escala nao precisa ser inteira).
			sx := x * plan.face.size / avatarSize
			sy := y * plan.face.size / avatarSize
			c := face[sy*plan.face.size+sx]
			if useHat {
				hx := x * plan.hat.size / avatarSize
				hy := y * plan.hat.size / avatarSize
				c = sourceOver(pixelAt(s, plan.hat.x+hx, plan.hat.y+hy), c)
			}
			out.SetRGBA(x, y, c)
		}
	}

	var buf bytes.Buffer
	if err := png.Encode(&buf, out); err != nil {
		info.Reason = "falha ao codificar a imagem"
		return avatarResult{Reason: info.Reason, Info: info}
	}
	sum := sha256.Sum256(buf.Bytes())
	info.Layout = plan.layout
	info.Rendered = true
	return avatarResult{
		Available: true,
		PNG:       buf.Bytes(),
		Hash:      hex.EncodeToString(sum[:])[:16],
		Layout:    plan.layout,
		Info:      info,
	}
}

// pixelAt reads one pixel with explicit bounds checking.
func pixelAt(s skin.Skin, x, y int) color.RGBA {
	w, h := s.Bounds().Dx(), s.Bounds().Dy()
	if x < 0 || y < 0 || x >= w || y >= h {
		return color.RGBA{}
	}
	i := (y*w + x) * 4
	if i+3 >= len(s.Pix) {
		return color.RGBA{}
	}
	return color.RGBA{R: s.Pix[i], G: s.Pix[i+1], B: s.Pix[i+2], A: s.Pix[i+3]}
}

// sourceOver composes src over dst with straight (non premultiplied) alpha.
func sourceOver(src, dst color.RGBA) color.RGBA {
	if src.A == 0 {
		return dst
	}
	if src.A == 255 {
		return src
	}
	sa := float64(src.A) / 255
	da := float64(dst.A) / 255
	outA := sa + da*(1-sa)
	if outA <= 0 {
		return color.RGBA{}
	}
	blend := func(s, d uint8) uint8 {
		v := (float64(s)*sa + float64(d)*da*(1-sa)) / outA
		if v < 0 {
			v = 0
		}
		if v > 255 {
			v = 255
		}
		return uint8(v + 0.5)
	}
	return color.RGBA{R: blend(src.R, dst.R), G: blend(src.G, dst.G), B: blend(src.B, dst.B), A: uint8(outA*255 + 0.5)}
}

// uniformOpaqueBlack reports whether the region is entirely opaque black, the
// classic artifact of old skins that never drew the outer layer.
func uniformOpaqueBlack(s skin.Skin, x0, y0, size int) bool {
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			c := pixelAt(s, x0+x, y0+y)
			if c.A != 255 || c.R != 0 || c.G != 0 || c.B != 0 {
				return false
			}
		}
	}
	return true
}
