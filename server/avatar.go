package main

// Miniaturas quadradas a partir da skin real da sessão Bedrock.
//
// Pix chega em RGBA direto (alfa NÃO pré-multiplicado). Por isso a composição e
// o PNG usam image.NRGBA/color.NRGBA; image.RGBA guardaria canais já
// multiplicados pelo alfa e escureceria transparência parcial.
//
// O modelo é interpretado, não procurado por palavras. O caminho suportado é o
// cubo direto do osso head, com box UV ou UV por face (north/front). Malhas
// livres, cabeças em descendentes e UV fora da textura são recusados com código
// próprio. A amostra Persona real pode ser exportada por ação explícita no app;
// nenhuma estrutura de modelo é inventada aqui.

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

const (
	maxAvatarModelBytes = 96 * 1024
	maxAvatarBones      = 512
	maxAvatarCubes      = 4096
)

// Códigos estáveis para a lista curta e o diagnóstico completo.
const (
	avatarOK                = "ok"
	avatarTextureDimensions = "texture_dimensions"
	avatarTextureSize       = "texture_size"
	avatarModelTooLarge     = "model_too_large"
	avatarModelParse        = "model_parse"
	avatarModelLimits       = "model_limits"
	avatarGeometrySelection = "geometry_selection"
	avatarHeadMissing       = "head_bone_missing"
	avatarHeadWithoutCubes  = "head_without_cubes"
	avatarHeadDescendant    = "head_in_descendant"
	avatarHeadMultiple      = "head_multiple_cubes"
	avatarOverlayOnly       = "head_overlay_only"
	avatarUVPerFaceMissing  = "uv_per_face_missing"
	avatarUVOutside         = "uv_out_of_texture"
	avatarUVInvalid         = "uv_invalid"
	avatarUnsupportedMesh   = "unsupported_mesh"
	avatarUnsupportedLayout = "unsupported_layout"
	avatarPersonaNoModel    = "persona_model_missing"
	avatarEncodeFailed      = "png_encode_failed"
)

type avatarResult struct {
	Available bool
	PNG       []byte
	Hash      string
	Layout    string
	Reason    string
	Info      avatarDiagnostics
}

type avatarDiagnostics struct {
	Code            string `json:"code"`
	Persona         bool   `json:"persona"`
	TexWidth        int    `json:"tex_w"`
	TexHeight       int    `json:"tex_h"`
	PixelBytes      int    `json:"pix_bytes"`
	ModelBytes      int    `json:"model_bytes"`
	FormatVersion   string `json:"format_version,omitempty"`
	Geometry        string `json:"geometry"`
	SelectedBy      string `json:"selected_by,omitempty"`
	BoneCount       int    `json:"bone_count"`
	CubeCount       int    `json:"cube_count"`
	HeadBone        bool   `json:"head_bone"`
	HeadCubes       int    `json:"head_cubes"`
	DescendantCubes int    `json:"descendant_cubes"`
	UVMode          string `json:"uv_mode,omitempty"`
	UVOutside       bool   `json:"uv_outside"`
	Mesh            bool   `json:"mesh"`
	Layout          string `json:"layout"`
	HeadCube        bool   `json:"head_cube"`
	Rendered        bool   `json:"rendered"`
	Reason          string `json:"reason"`
}

type avatarRect struct{ x, y, size int }

func (r avatarRect) inside(w, h int) bool {
	return r.size > 0 && r.x >= 0 && r.y >= 0 && r.x+r.size <= w && r.y+r.size <= h
}

type avatarPlan struct {
	face   avatarRect
	hat    avatarRect
	hasHat bool
	layout string
	legacy bool
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
	Identifier    string  `json:"identifier"`
	TextureWidth  float64 `json:"texture_width"`
	TextureHeight float64 `json:"texture_height"`
}

type geoBone struct {
	Name          string          `json:"name"`
	Parent        string          `json:"parent"`
	Pivot         []float64       `json:"pivot"`
	Cubes         []geoCube       `json:"cubes"`
	TextureMeshes json.RawMessage `json:"texture_meshes"`
}

type geoCube struct {
	Origin   []float64       `json:"origin"`
	Size     []float64       `json:"size"`
	UV       json.RawMessage `json:"uv"`
	Inflate  float64         `json:"inflate"`
	Mirror   bool            `json:"mirror"`
	Vertices json.RawMessage `json:"vertices"`
	Faces    json.RawMessage `json:"faces"`
	Mesh     json.RawMessage `json:"mesh"`
}

type geoFaceUV struct {
	UV     []float64 `json:"uv"`
	UVSize []float64 `json:"uv_size"`
}

type geoUV struct {
	mode  string
	box   []float64
	faces map[string]geoFaceUV
}

func parseCubeUV(raw json.RawMessage) (geoUV, string) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return geoUV{}, avatarUVInvalid
	}
	if raw[0] == '[' {
		var box []float64
		if err := json.Unmarshal(raw, &box); err != nil || len(box) < 2 || !finite(box[0]) || !finite(box[1]) {
			return geoUV{}, avatarUVInvalid
		}
		return geoUV{mode: "box", box: box[:2]}, ""
	}
	if raw[0] == '{' {
		var faces map[string]geoFaceUV
		if err := json.Unmarshal(raw, &faces); err != nil || len(faces) == 0 {
			return geoUV{}, avatarUVInvalid
		}
		for _, f := range faces {
			if len(f.UV) < 2 || len(f.UVSize) < 2 || !finite(f.UV[0]) || !finite(f.UV[1]) || !finite(f.UVSize[0]) || !finite(f.UVSize[1]) {
				return geoUV{}, avatarUVInvalid
			}
		}
		return geoUV{mode: "per-face", faces: faces}, ""
	}
	return geoUV{}, avatarUVInvalid
}

func finite(v float64) bool { return !math.IsNaN(v) && !math.IsInf(v, 0) }

// ---------------------------------------------------------------------- plano

func planAvatar(s skin.Skin) (avatarPlan, avatarDiagnostics, string) {
	bounds := s.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	info := avatarDiagnostics{
		Code: avatarOK, Persona: s.Persona, TexWidth: w, TexHeight: h,
		PixelBytes: len(s.Pix), ModelBytes: len(s.Model),
	}
	fail := func(code, reason string) (avatarPlan, avatarDiagnostics, string) {
		info.Code = code
		info.Reason = reason
		if s.Persona {
			info.Reason = "skin Persona: " + reason
		}
		return avatarPlan{}, info, info.Reason
	}
	if w <= 0 || h <= 0 || w > 256 || h > 256 {
		return fail(avatarTextureDimensions, fmt.Sprintf("dimensões de skin inesperadas: %dx%d", w, h))
	}
	if len(s.Pix) != w*h*4 {
		return fail(avatarTextureSize, fmt.Sprintf("textura com tamanho inconsistente: %d byte(s) para %dx%d (esperado %d)", len(s.Pix), w, h, w*h*4))
	}

	model, code, modelErr := decodeGeometry(s.Model)
	info.FormatVersion = model.FormatVersion
	if modelErr != "" {
		return fail(code, modelErr)
	}
	if len(s.Model) == 0 {
		if s.Persona {
			return fail(avatarPersonaNoModel, "o cliente não enviou o JSON da geometria; exporte a amostra pelo app para analisar o caso real")
		}
		plan, ok := standardPlan(w, h)
		if !ok {
			return fail(avatarUnsupportedLayout, fmt.Sprintf("textura %dx%d fora dos layouts clássicos 64x64, 64x32 e 128x128", w, h))
		}
		info.Layout = plan.layout
		info.UVMode = "standard"
		return plan, info, ""
	}
	geometry, selectedBy, geometryReason := selectGeometry(model, s.ModelConfig.Default)
	info.Geometry = geometry.Description.Identifier
	info.SelectedBy = selectedBy
	info.BoneCount = len(geometry.Bones)
	for _, b := range geometry.Bones {
		info.CubeCount += len(b.Cubes)
	}
	if geometryReason != "" {
		return fail(avatarGeometrySelection, geometryReason)
	}

	// Modelo explícito: localizar head e inspecionar cubos/descendentes.
	head, headCode, headReason := findHeadBone(geometry)
	info.HeadBone = headCode == ""
	if headCode != "" {
		// Somente humanoid sem nenhum osso pode usar o layout clássico conhecido.
		if len(geometry.Bones) == 0 && strings.HasPrefix(strings.ToLower(geometry.Description.Identifier), "geometry.humanoid") {
			plan, ok := standardPlan(w, h)
			if !ok {
				return fail(avatarUnsupportedLayout, fmt.Sprintf("textura %dx%d fora dos layouts humanoides conhecidos", w, h))
			}
			info.Layout = plan.layout
			info.UVMode = "standard"
			return plan, info, ""
		}
		return fail(headCode, headReason)
	}
	info.HeadCubes = len(head.Cubes)
	info.DescendantCubes = descendantCubeCount(geometry, head.Name)
	if len(head.TextureMeshes) > 0 && !bytes.Equal(bytes.TrimSpace(head.TextureMeshes), []byte("null")) {
		info.Mesh = true
		return fail(avatarUnsupportedMesh, "osso head usa texture_meshes (malha livre), não cubos com UV de caixa")
	}
	if len(head.Cubes) == 0 {
		if info.DescendantCubes > 0 {
			return fail(avatarHeadDescendant, fmt.Sprintf("osso head não tem cubos diretos; %d cubo(s) estão em descendentes, cuja transformação não é segura para recortar o rosto", info.DescendantCubes))
		}
		return fail(avatarHeadWithoutCubes, "osso head encontrado, mas sem cubos diretos")
	}
	plan, code, reason := planFromHeadCube(s, geometry, head, &info)
	if code != "" {
		return fail(code, reason)
	}
	info.HeadCube = true
	info.Layout = plan.layout
	return plan, info, ""
}

func decodeGeometry(raw []byte) (geoDocument, string, string) {
	if len(raw) == 0 {
		return geoDocument{}, "", ""
	}
	if len(raw) > maxAvatarModelBytes {
		return geoDocument{}, avatarModelTooLarge, fmt.Sprintf("modelo de geometria grande demais (%d bytes; limite %d)", len(raw), maxAvatarModelBytes)
	}
	var doc geoDocument
	if err := json.Unmarshal(raw, &doc); err != nil {
		return geoDocument{}, avatarModelParse, "geometria enviada pelo cliente não é JSON válido: " + err.Error()
	}
	bones, cubes := 0, 0
	for _, g := range doc.Geometry {
		bones += len(g.Bones)
		for _, b := range g.Bones {
			cubes += len(b.Cubes)
		}
	}
	if bones > maxAvatarBones || cubes > maxAvatarCubes {
		return geoDocument{}, avatarModelLimits, fmt.Sprintf("geometria excede o limite (%d ossos, %d cubos)", bones, cubes)
	}
	return doc, "", ""
}

func selectGeometry(doc geoDocument, defaultName string) (geoGeometry, string, string) {
	if len(doc.Geometry) == 0 {
		return geoGeometry{}, "", "modelo sem entradas minecraft:geometry"
	}
	normalized := normalizeGeometryID(defaultName)
	if normalized != "" {
		for _, g := range doc.Geometry {
			if normalizeGeometryID(g.Description.Identifier) == normalized {
				if strings.TrimSpace(g.Description.Identifier) == "" {
					return geoGeometry{}, "", "geometria selecionada sem identifier"
				}
				return g, "ModelConfig.Default", ""
			}
		}
		return geoGeometry{}, "", fmt.Sprintf("geometria %q indicada pela skin não veio no modelo", defaultName)
	}
	if len(doc.Geometry) == 1 {
		if strings.TrimSpace(doc.Geometry[0].Description.Identifier) == "" {
			return geoGeometry{}, "", "geometria única sem identifier"
		}
		return doc.Geometry[0], "single", ""
	}
	var humanoid *geoGeometry
	for i := range doc.Geometry {
		if strings.HasPrefix(strings.ToLower(doc.Geometry[i].Description.Identifier), "geometry.humanoid") {
			if humanoid != nil {
				return geoGeometry{}, "", "mais de uma geometria humanoid e nenhuma seleção explícita do cliente"
			}
			humanoid = &doc.Geometry[i]
		}
	}
	if humanoid != nil {
		return *humanoid, "humanoid-fallback", ""
	}
	return geoGeometry{}, "", "múltiplas geometrias sem ModelConfig.Default; não é possível escolher com segurança"
}

func normalizeGeometryID(id string) string {
	id = strings.ToLower(strings.TrimSpace(id))
	id = strings.ReplaceAll(id, "_", "")
	id = strings.ReplaceAll(id, ".", "")
	return id
}

func isHeadName(name string) bool {
	name = strings.ToLower(strings.TrimSpace(name))
	return name == "head" || strings.HasSuffix(name, ".head") || strings.HasSuffix(name, "_head")
}

func findHeadBone(geometry geoGeometry) (geoBone, string, string) {
	var head *geoBone
	for i := range geometry.Bones {
		if isHeadName(geometry.Bones[i].Name) {
			if head != nil {
				return geoBone{}, avatarGeometrySelection, "mais de um osso de cabeça; a seleção seria ambígua"
			}
			head = &geometry.Bones[i]
		}
	}
	if head == nil {
		return geoBone{}, avatarHeadMissing, "osso da cabeça ausente na geometria selecionada"
	}
	return *head, "", ""
}

func descendantCubeCount(geometry geoGeometry, headName string) int {
	children := map[string][]geoBone{}
	for _, b := range geometry.Bones {
		children[b.Parent] = append(children[b.Parent], b)
	}
	var visit func(string, map[string]bool) int
	visit = func(name string, seen map[string]bool) int {
		if seen[name] {
			return 0
		}
		seen[name] = true
		n := 0
		for _, child := range children[name] {
			n += len(child.Cubes) + visit(child.Name, seen)
		}
		return n
	}
	return visit(headName, map[string]bool{})
}

func planFromHeadCube(s skin.Skin, geometry geoGeometry, head geoBone, info *avatarDiagnostics) (avatarPlan, string, string) {
	w, h := s.Bounds().Dx(), s.Bounds().Dy()
	scaleX, scaleY, code, reason := geometryScale(geometry, w, h)
	if code != "" {
		return avatarPlan{}, code, reason
	}
	var bases, overlays []*geoCube
	for i := range head.Cubes {
		cube := &head.Cubes[i]
		if len(cube.Vertices) > 0 || len(cube.Faces) > 0 || len(cube.Mesh) > 0 {
			info.Mesh = true
			return avatarPlan{}, avatarUnsupportedMesh, "cubo da cabeça contém malha livre (vertices/faces/mesh), não box UV"
		}
		if !finite(cube.Inflate) {
			return avatarPlan{}, avatarUVInvalid, "inflate inválido no cubo da cabeça"
		}
		if cube.Inflate > 0 {
			overlays = append(overlays, cube)
		} else {
			bases = append(bases, cube)
		}
	}
	if len(bases) == 0 {
		return avatarPlan{}, avatarOverlayOnly, "osso head só tem cubos de camada externa (inflate > 0), sem cubo base do rosto"
	}
	if len(bases) > 1 {
		return avatarPlan{}, avatarHeadMultiple, fmt.Sprintf("osso head tem %d cubos base; não é possível escolher o rosto sem adivinhar", len(bases))
	}
	base := bases[0]
	uv, code := parseCubeUV(base.UV)
	if code != "" {
		return avatarPlan{}, code, "UV do cubo da cabeça ausente ou inválido"
	}
	info.UVMode = uv.mode
	face, code, reason := frontFaceOf(*base, uv, scaleX, scaleY)
	if code != "" {
		if code == avatarUVOutside {
			info.UVOutside = true
		}
		return avatarPlan{}, code, reason
	}
	if !face.inside(w, h) {
		info.UVOutside = true
		return avatarPlan{}, avatarUVOutside, fmt.Sprintf("UV do rosto fora da textura %dx%d: [%d,%d +%d]", w, h, face.x, face.y, face.size)
	}
	plan := avatarPlan{face: face, layout: fmt.Sprintf("%s %dx%d", shortGeometryID(geometry.Description.Identifier), w, h)}
	if len(overlays) > 0 {
		overlay := overlays[0]
		ouv, ocode := parseCubeUV(overlay.UV)
		if ocode == "" {
			hat, hcode, _ := frontFaceOf(*overlay, ouv, scaleX, scaleY)
			if hcode == "" && hat.inside(w, h) {
				plan.hat, plan.hasHat = hat, true
			} else if hcode == avatarUVOutside || !hat.inside(w, h) {
				info.UVOutside = true
				info.Reason = "camada externa ignorada: UV fora da textura"
			}
		}
	}
	return plan, "", ""
}

func geometryScale(g geoGeometry, texWidth, texHeight int) (float64, float64, string, string) {
	w, h := g.Description.TextureWidth, g.Description.TextureHeight
	if w == 0 && h == 0 {
		w, h = 64, 64
	}
	if !finite(w) || !finite(h) || w <= 0 || h <= 0 {
		return 0, 0, avatarUVInvalid, "texture_width/texture_height da geometria inválidos"
	}
	return float64(texWidth) / w, float64(texHeight) / h, "", ""
}

// frontFaceOf aceita box UV e UV por face. No formato por face usamos north
// (face frontal negativa de Z); "front" é aceito como alias explícito. Não
// escolhemos south/east/west por adivinhação quando north/front falta.
func frontFaceOf(cube geoCube, uv geoUV, scaleX, scaleY float64) (avatarRect, string, string) {
	if uv.mode == "per-face" {
		face, ok := uv.faces["front"]
		if !ok {
			face, ok = uv.faces["north"]
		}
		if !ok {
			return avatarRect{}, avatarUVPerFaceMissing, "UV por face sem north/front; exporte o modelo para identificar a face correta"
		}
		x := int(math.Round(face.UV[0] * scaleX))
		y := int(math.Round(face.UV[1] * scaleY))
		w := int(math.Round(math.Abs(face.UVSize[0]) * scaleX))
		h := int(math.Round(math.Abs(face.UVSize[1]) * scaleY))
		if w <= 0 || h <= 0 || w != h {
			return avatarRect{}, avatarUnsupportedMesh, fmt.Sprintf("UV por face do rosto não é quadrada (%dx%d)", w, h)
		}
		if x < 0 || y < 0 {
			return avatarRect{}, avatarUVOutside, "UV por face negativa"
		}
		return avatarRect{x: x, y: y, size: w}, "", ""
	}
	if len(cube.Size) < 3 || !finite(cube.Size[0]) || !finite(cube.Size[1]) || !finite(cube.Size[2]) {
		return avatarRect{}, avatarUnsupportedMesh, "cubo da cabeça sem size 3D válido"
	}
	width, height, depth := math.Abs(cube.Size[0]), math.Abs(cube.Size[1]), math.Abs(cube.Size[2])
	if width < 1 || height < 1 || math.Abs(width-height) > 0.001 {
		return avatarRect{}, avatarUnsupportedMesh, "face do cubo da cabeça não é quadrada nas dimensões do modelo"
	}
	x := int(math.Round((uv.box[0] + depth) * scaleX))
	y := int(math.Round((uv.box[1] + depth) * scaleY))
	size := int(math.Round(width * scaleX))
	if size <= 0 || x < 0 || y < 0 {
		return avatarRect{}, avatarUVOutside, "UV de caixa fora da textura"
	}
	return avatarRect{x: x, y: y, size: size}, "", ""
}

func shortGeometryID(id string) string {
	trimmed := strings.TrimSpace(id)
	if trimmed == "" {
		return "geometria"
	}
	return trimmed
}

func standardPlan(w, h int) (avatarPlan, bool) {
	switch {
	case w == 64 && h == 64:
		return avatarPlan{face: avatarRect{8, 8, 8}, hat: avatarRect{40, 8, 8}, hasHat: true, layout: "64x64"}, true
	case w == 64 && h == 32:
		return avatarPlan{face: avatarRect{8, 8, 8}, hat: avatarRect{40, 8, 8}, hasHat: true, layout: "64x32", legacy: true}, true
	case w == 128 && h == 128:
		return avatarPlan{face: avatarRect{16, 16, 16}, hat: avatarRect{80, 16, 16}, hasHat: true, layout: "128x128"}, true
	}
	return avatarPlan{}, false
}

// ------------------------------------------------------------------ render

func renderAvatar(s skin.Skin) avatarResult {
	plan, info, reason := planAvatar(s)
	info.Rendered = false
	if reason != "" {
		info.Reason = reason
		return avatarResult{Reason: reason, Info: info}
	}
	useHat := plan.hasHat
	if useHat && plan.legacy && uniformOpaqueBlack(s, plan.hat.x, plan.hat.y, plan.hat.size) {
		useHat = false
	}
	out := image.NewNRGBA(image.Rect(0, 0, avatarSize, avatarSize))
	for y := 0; y < avatarSize; y++ {
		for x := 0; x < avatarSize; x++ {
			sx := x * plan.face.size / avatarSize
			sy := y * plan.face.size / avatarSize
			c := pixelAt(s, plan.face.x+sx, plan.face.y+sy)
			if useHat {
				hx := x * plan.hat.size / avatarSize
				hy := y * plan.hat.size / avatarSize
				c = sourceOver(pixelAt(s, plan.hat.x+hx, plan.hat.y+hy), c)
			}
			out.SetNRGBA(x, y, c)
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, out); err != nil {
		info.Code = avatarEncodeFailed
		info.Reason = "falha ao codificar a imagem: " + err.Error()
		return avatarResult{Reason: info.Reason, Info: info}
	}
	sum := sha256.Sum256(buf.Bytes())
	info.Layout = plan.layout
	info.Rendered = true
	info.Code = avatarOK
	return avatarResult{Available: true, PNG: buf.Bytes(), Hash: hex.EncodeToString(sum[:])[:16], Layout: plan.layout, Info: info}
}

func pixelAt(s skin.Skin, x, y int) color.NRGBA {
	w, h := s.Bounds().Dx(), s.Bounds().Dy()
	if x < 0 || y < 0 || x >= w || y >= h {
		return color.NRGBA{}
	}
	i := (y*w + x) * 4
	if i+3 >= len(s.Pix) {
		return color.NRGBA{}
	}
	return color.NRGBA{R: s.Pix[i], G: s.Pix[i+1], B: s.Pix[i+2], A: s.Pix[i+3]}
}

// sourceOver com alfa direto (NRGBA), sem pré-multiplicar os canais de entrada.
func sourceOver(src, dst color.NRGBA) color.NRGBA {
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
		return color.NRGBA{}
	}
	blend := func(s, d uint8) uint8 {
		v := (float64(s)*sa + float64(d)*da*(1-sa)) / outA
		return uint8(math.Max(0, math.Min(255, v)) + 0.5)
	}
	return color.NRGBA{R: blend(src.R, dst.R), G: blend(src.G, dst.G), B: blend(src.B, dst.B), A: uint8(outA*255 + 0.5)}
}

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

// ------------------------------------------------------------------ export

const maxSkinExportModelBytes = 1024 * 1024

type skinExport struct {
	TexturePNG []byte
	ModelJSON  []byte
	Info       avatarDiagnostics
	Reason     string
}

// buildSkinExport exporta somente a textura recebida e o JSON do modelo. Não
// inclui skin ID, FullID, tokens, ClientData, login, capa ou animações.
func buildSkinExport(s skin.Skin) skinExport {
	res := renderAvatar(s)
	out := skinExport{Info: res.Info, Reason: res.Reason}
	w, h := s.Bounds().Dx(), s.Bounds().Dy()
	if w > 0 && h > 0 && w <= 1024 && h <= 1024 && len(s.Pix) == w*h*4 {
		img := image.NewNRGBA(image.Rect(0, 0, w, h))
		for y := 0; y < h; y++ {
			copy(img.Pix[y*img.Stride:y*img.Stride+w*4], s.Pix[y*w*4:(y+1)*w*4])
		}
		var buf bytes.Buffer
		if err := png.Encode(&buf, img); err == nil {
			out.TexturePNG = buf.Bytes()
		}
	}
	if len(s.Model) > 0 && len(s.Model) <= maxSkinExportModelBytes {
		out.ModelJSON = append([]byte(nil), s.Model...)
	}
	return out
}
