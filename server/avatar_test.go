package main

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"strings"
	"testing"

	"github.com/df-mc/dragonfly/server/player/skin"
)

func fillSkin(s *skin.Skin, x0, y0, size int, c color.RGBA) {
	w := s.Bounds().Dx()
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			i := ((y0+y)*w + (x0 + x)) * 4
			s.Pix[i] = c.R
			s.Pix[i+1] = c.G
			s.Pix[i+2] = c.B
			s.Pix[i+3] = c.A
		}
	}
}

func decodeAvatar(t *testing.T, res avatarResult) image.Image {
	t.Helper()
	if !res.Available {
		t.Fatalf("avatar indisponível: %s", res.Reason)
	}
	img, err := png.Decode(bytes.NewReader(res.PNG))
	if err != nil {
		t.Fatalf("PNG inválido: %v", err)
	}
	b := img.Bounds()
	if b.Dx() != avatarSize || b.Dy() != avatarSize {
		t.Fatalf("miniatura %dx%d, esperava %dx%d", b.Dx(), b.Dy(), avatarSize, avatarSize)
	}
	return img
}

// rgbaAt devolve o pixel sem assumir o modelo interno do PNG (o codificador usa
// NRGBA quando há transparência).
func rgbaAt(img image.Image, x, y int) color.NRGBA {
	return color.NRGBAModel.Convert(img.At(x, y)).(color.NRGBA)
}

func near(t *testing.T, got, want uint8, label string) {
	t.Helper()
	if got > want+2 || got+2 < want {
		t.Fatalf("%s: %d, esperava ~%d", label, got, want)
	}
}

// TestAvatarFaceCrop checks the 64x64 crop coordinates with known colours.
func TestAvatarFaceCrop(t *testing.T) {
	s := skin.New(64, 64)
	face := color.RGBA{R: 10, G: 20, B: 30, A: 255}
	fillSkin(&s, 8, 8, 4, face)

	res := renderAvatar(s)
	if res.Layout != "64x64" {
		t.Fatalf("layout %q", res.Layout)
	}
	img := decodeAvatar(t, res)
	// Cada pixel da textura vira um bloco de 8x8 na miniatura; 4 pixels
	// preenchidos ocupam só o canto superior esquerdo.
	got := rgbaAt(img, 0, 0)
	if got.R != face.R || got.G != face.G || got.B != face.B || got.A != 255 {
		t.Fatalf("pixel (0,0) = %v, esperava %v", got, face)
	}
	// Fora do rosto a textura é transparente.
	if a := rgbaAt(img, 63, 63).A; a != 0 {
		t.Fatalf("canto deveria ser transparente, alfa=%d", a)
	}
}

// TestAvatarOuterLayerOverlay checks that the hat layer lands on top of the face.
func TestAvatarOuterLayerOverlay(t *testing.T) {
	s := skin.New(64, 64)
	face := color.RGBA{R: 0, G: 0, B: 255, A: 255}
	hat := color.RGBA{R: 255, G: 0, B: 0, A: 255}
	fillSkin(&s, 8, 8, 8, face)
	fillSkin(&s, 40, 8, 1, hat) // um único pixel da camada externa

	img := decodeAvatar(t, renderAvatar(s))
	got := rgbaAt(img, 0, 0)
	if got.R != hat.R || got.B != hat.B {
		t.Fatalf("camada externa não foi composta: %v", got)
	}
	// O pixel vizinho continua sendo o rosto.
	if other := rgbaAt(img, 8, 0); other.B != face.B {
		t.Fatalf("vizinhança alterada: %v", other)
	}
}

// TestAvatarSemiTransparentOverlay covers partial alpha (source over on
// straight, non premultiplied RGBA).
func TestAvatarSemiTransparentOverlay(t *testing.T) {
	s := skin.New(64, 64)
	fillSkin(&s, 8, 8, 8, color.RGBA{R: 0, G: 0, B: 255, A: 255})
	fillSkin(&s, 40, 8, 8, color.RGBA{R: 255, G: 0, B: 0, A: 128})

	got := rgbaAt(decodeAvatar(t, renderAvatar(s)), 0, 0)
	near(t, got.R, 128, "R")
	near(t, got.B, 127, "B")
	if got.A != 255 {
		t.Fatalf("alfa final %d, esperava 255", got.A)
	}
}

// TestAvatarLegacyOpaqueHatIgnored reproduces the classic 64x32 skin whose
// unused hat area is opaque black: the compatible behaviour is to ignore it.
func TestAvatarLegacyOpaqueHatIgnored(t *testing.T) {
	s := skin.New(64, 32)
	face := color.RGBA{R: 0, G: 200, B: 0, A: 255}
	fillSkin(&s, 8, 8, 8, face)
	fillSkin(&s, 40, 8, 8, color.RGBA{R: 0, G: 0, B: 0, A: 255})

	res := renderAvatar(s)
	if res.Layout != "64x32" {
		t.Fatalf("layout %q", res.Layout)
	}
	got := rgbaAt(decodeAvatar(t, res), 0, 0)
	if got.G != face.G || got.R != 0 || got.B != 0 {
		t.Fatalf("camada externa preta deveria ser ignorada: %v", got)
	}
}

// TestAvatarDoubledLayout covers the 128x128 layout.
func TestAvatarDoubledLayout(t *testing.T) {
	s := skin.New(128, 128)
	face := color.RGBA{R: 5, G: 6, B: 7, A: 255}
	fillSkin(&s, 16, 16, 16, face)

	res := renderAvatar(s)
	if res.Layout != "128x128" {
		t.Fatalf("layout %q", res.Layout)
	}
	img := decodeAvatar(t, res)
	// 16x16 pixels de textura em 64x64 => blocos 4x4.
	if got := rgbaAt(img, 0, 0); got.R != face.R || got.G != face.G || got.B != face.B {
		t.Fatalf("pixel (0,0) = %v", got)
	}
	if got := rgbaAt(img, 63, 63); got.R != face.R {
		t.Fatalf("pixel (63,63) = %v", got)
	}
}

// TestAvatarUnavailable makes sure unsupported skins say so instead of showing
// a wrong face.
func TestAvatarUnavailable(t *testing.T) {
	persona := skin.New(64, 64)
	persona.Persona = true
	if res := renderAvatar(persona); res.Available || res.Reason == "" {
		t.Fatalf("persona deveria ser indisponível: %+v", res)
	}

	custom := skin.New(64, 64)
	custom.Model = []byte(`{"minecraft:geometry":[{"description":{"identifier":"geometry.custom.gear"}}]}`)
	if res := renderAvatar(custom); res.Available || res.Reason == "" {
		t.Fatalf("geometria personalizada deveria ser indisponível: %+v", res)
	}

	humanoid := skin.New(64, 64)
	humanoid.Model = []byte(`{"minecraft:geometry":[{"description":{"identifier":"geometry.humanoid.custom"}}]}`)
	if res := renderAvatar(humanoid); !res.Available {
		t.Fatalf("geometria humanoid deveria ser suportada: %+v", res)
	}

	broken := skin.New(64, 64)
	broken.Pix = broken.Pix[:len(broken.Pix)-3]
	if res := renderAvatar(broken); res.Available || res.Reason == "" {
		t.Fatalf("textura inconsistente deveria ser indisponível: %+v", res)
	}

	odd := skin.New(64, 64)
	odd.Persona = false
	if res := renderAvatar(odd); !res.Available {
		t.Fatalf("skin padrão totalmente transparente ainda deve gerar imagem: %+v", res)
	}
}

// TestAvatarHashChangesWithSkin proves the cache key follows the appearance.
func TestAvatarHashChangesWithSkin(t *testing.T) {
	s := skin.New(64, 64)
	fillSkin(&s, 8, 8, 8, color.RGBA{R: 1, G: 2, B: 3, A: 255})
	first := renderAvatar(s)
	fillSkin(&s, 8, 8, 8, color.RGBA{R: 9, G: 9, B: 9, A: 255})
	second := renderAvatar(s)
	if first.Hash == second.Hash {
		t.Fatalf("hash não mudou com a skin: %s", first.Hash)
	}
}

// geometryJSON monta um modelo mínimo no formato do jogo.
func geometryJSON(identifier string, textureWidth float64, cubes ...string) []byte {
	tw := ""
	if textureWidth > 0 {
		tw = fmt.Sprintf(`,"texture_width":%g,"texture_height":%g`, textureWidth, textureWidth)
	}
	return []byte(fmt.Sprintf(
		`{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":%q%s},`+
			`"bones":[{"name":"head","pivot":[0,24,0],"cubes":[%s]}]}]}`,
		identifier, tw, strings.Join(cubes, ","),
	))
}

func avatarCube(uvX, uvY float64, size, inflate float64) string {
	return fmt.Sprintf(`{"origin":[-4,24,-4],"size":[%g,%g,%g],"uv":[%g,%g],"inflate":%g}`,
		size, size, size, uvX, uvY, inflate)
}

// TestAvatarGeometryHeadCube prova que o recorte vem do cubo da cabeça enviado
// no modelo, e não de uma busca por palavra no JSON.
func TestAvatarGeometryHeadCube(t *testing.T) {
	s := skin.New(64, 64)
	face := color.RGBA{R: 12, G: 34, B: 56, A: 255}
	fillSkin(&s, 8, 8, 8, face)
	s.Model = geometryJSON("geometry.humanoid.customSlim", 64, avatarCube(0, 0, 8, 0))
	s.ModelConfig.Default = "geometry.humanoid.customSlim"

	res := renderAvatar(s)
	img := decodeAvatar(t, res)
	if !res.Info.HeadCube || !res.Info.Rendered {
		t.Fatalf("diagnóstico sem cubo de cabeça: %+v", res.Info)
	}
	if got := rgbaAt(img, 0, 0); got.R != face.R || got.G != face.G || got.B != face.B {
		t.Fatalf("rosto fora da UV do cubo: %v", got)
	}
	if res.Info.TexWidth != 64 || res.Info.PixelBytes != 64*64*4 {
		t.Fatalf("diagnóstico sem dimensões: %+v", res.Info)
	}
}

// TestAvatarGeometryHatOverlay cobre a camada externa descrita no modelo.
func TestAvatarGeometryHatOverlay(t *testing.T) {
	s := skin.New(64, 64)
	fillSkin(&s, 8, 8, 8, color.RGBA{R: 0, G: 0, B: 255, A: 255})
	fillSkin(&s, 40, 8, 8, color.RGBA{R: 255, G: 0, B: 0, A: 255})
	s.Model = geometryJSON("geometry.humanoid.custom", 64,
		avatarCube(0, 0, 8, 0),
		avatarCube(32, 0, 8, 0.5),
	)
	got := rgbaAt(decodeAvatar(t, renderAvatar(s)), 0, 0)
	if got.R != 255 || got.B != 0 {
		t.Fatalf("camada externa do modelo não foi composta: %v", got)
	}
}

// TestAvatarGeometryDoubled128 usa as UVs do próprio modelo numa textura 128.
func TestAvatarGeometryDoubled128(t *testing.T) {
	s := skin.New(128, 128)
	face := color.RGBA{R: 1, G: 2, B: 3, A: 255}
	fillSkin(&s, 16, 16, 16, face)
	// Geometria no espaço 64x64 sobre textura 128: as UVs dobram.
	s.Model = geometryJSON("geometry.humanoid.custom", 64, avatarCube(0, 0, 8, 0))
	res := renderAvatar(s)
	got := rgbaAt(decodeAvatar(t, res), 0, 0)
	if got.R != face.R {
		t.Fatalf("UV 128 não foi dobrada: %v (layout %s)", got, res.Layout)
	}
}

// TestAvatarGeometryWithoutHeadCube recusa geometria personalizada incompleta
// com motivo, em vez de recortar coordenadas fixas.
func TestAvatarGeometryWithoutHeadCube(t *testing.T) {
	s := skin.New(64, 64)
	s.Model = []byte(`{"format_version":"1.12.0","minecraft:geometry":[{"description":` +
		`{"identifier":"geometry.custom.gear"},"bones":[{"name":"body","cubes":[]}]}]}`)
	res := renderAvatar(s)
	if res.Available || !strings.Contains(res.Reason, "cabeça") {
		t.Fatalf("esperava recusa com motivo da cabeça: %+v", res)
	}
	if res.Info.Geometry != "geometry.custom.gear" {
		t.Fatalf("diagnóstico sem identificador: %+v", res.Info)
	}
}

// TestAvatarPersonaWithGeometry tenta a renderização determinística quando o
// modelo Persona traz a cabeça; sem ela, declara a limitação.
func TestAvatarPersonaWithGeometry(t *testing.T) {
	withGeometry := skin.New(64, 64)
	withGeometry.Persona = true
	fillSkin(&withGeometry, 8, 8, 8, color.RGBA{R: 200, G: 100, B: 50, A: 255})
	withGeometry.Model = geometryJSON("persona.head", 64, avatarCube(0, 0, 8, 0))
	res := renderAvatar(withGeometry)
	if !res.Available {
		t.Fatalf("persona com cubo de cabeça deveria renderizar: %s", res.Reason)
	}
	if !res.Info.Persona {
		t.Fatalf("diagnóstico deveria marcar persona: %+v", res.Info)
	}

	withoutGeometry := skin.New(64, 64)
	withoutGeometry.Persona = true
	res = renderAvatar(withoutGeometry)
	if res.Available || !strings.Contains(res.Reason, "Persona") {
		t.Fatalf("persona sem geometria precisa de motivo específico: %+v", res)
	}
}

// TestAvatarModelLimits impede que um modelo gigante trave a sessão.
func TestAvatarModelLimits(t *testing.T) {
	s := skin.New(64, 64)
	s.Model = []byte(strings.Repeat("x", maxAvatarModelBytes+1))
	res := renderAvatar(s)
	if res.Available || !strings.Contains(res.Reason, "grande demais") {
		t.Fatalf("modelo gigante deveria ser recusado: %+v", res)
	}
}

func TestAvatarGeometryFailureCodes(t *testing.T) {
	cases := []struct {
		name    string
		model   string
		code    string
		persona bool
	}{
		{"head ausente", `{"minecraft:geometry":[{"description":{"identifier":"geometry.custom"},"bones":[{"name":"body","cubes":[]}]}]}`, avatarHeadMissing, false},
		{"head sem cubos", `{"minecraft:geometry":[{"description":{"identifier":"geometry.custom"},"bones":[{"name":"head","cubes":[]}]}]}`, avatarHeadWithoutCubes, false},
		{"descendente", `{"minecraft:geometry":[{"description":{"identifier":"geometry.custom"},"bones":[{"name":"head","cubes":[]},{"name":"helmet","parent":"head","cubes":[{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0]}]}]}]}`, avatarHeadDescendant, false},
		{"malha livre", `{"minecraft:geometry":[{"description":{"identifier":"geometry.custom"},"bones":[{"name":"head","texture_meshes":[{"texture":0}],"cubes":[]}]}]}`, avatarUnsupportedMesh, false},
		{"parse", `{`, avatarModelParse, false},
		{"selecao ausente", `{"minecraft:geometry":[{"description":{"identifier":"geometry.a"},"bones":[]},{"description":{"identifier":"geometry.b"},"bones":[]}]}`, avatarGeometrySelection, false},
		{"persona sem modelo", ``, avatarPersonaNoModel, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := skin.New(64, 64)
			s.Persona = tc.persona
			s.Model = []byte(tc.model)
			res := renderAvatar(s)
			if res.Available || res.Info.Code != tc.code {
				t.Fatalf("código %s esperado, veio %+v", tc.code, res)
			}
			if res.Reason == "" {
				t.Fatal("motivo completo vazio")
			}
		})
	}
}

func TestAvatarGeometryUVOutside(t *testing.T) {
	s := skin.New(64, 64)
	s.Model = geometryJSON("geometry.custom", 64, avatarCube(60, 60, 8, 0))
	res := renderAvatar(s)
	if res.Available || res.Info.Code != avatarUVOutside || !res.Info.UVOutside {
		t.Fatalf("UV fora da textura deveria ser específica: %+v", res)
	}
}

func TestAvatarGeometryPerFaceUV(t *testing.T) {
	s := skin.New(64, 64)
	face := color.RGBA{R: 21, G: 43, B: 65, A: 255}
	fillSkin(&s, 12, 20, 8, face)
	s.Model = []byte(`{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"geometry.persona.test","texture_width":64,"texture_height":64},"bones":[{"name":"head","cubes":[{"origin":[-4,24,-4],"size":[8,8,8],"uv":{"north":{"uv":[12,20],"uv_size":[8,8]},"south":{"uv":[0,0],"uv_size":[8,8]}}}]}]}]}`)
	res := renderAvatar(s)
	if !res.Available || res.Info.UVMode != "per-face" {
		t.Fatalf("UV por face suportada não renderizou: %+v", res)
	}
	if got := rgbaAt(decodeAvatar(t, res), 0, 0); got.R != face.R || got.G != face.G || got.B != face.B {
		t.Fatalf("recorte por face errado: %v", got)
	}
	missing := skin.New(64, 64)
	missing.Model = []byte(`{"minecraft:geometry":[{"description":{"identifier":"geometry.custom"},"bones":[{"name":"head","cubes":[{"size":[8,8,8],"uv":{"south":{"uv":[0,0],"uv_size":[8,8]}}}]}]}]}`)
	if r := renderAvatar(missing); r.Available || r.Info.Code != avatarUVPerFaceMissing {
		t.Fatalf("face frontal ausente deveria ser diagnosticada: %+v", r)
	}
}

func TestAvatarPartialAlphaStaysStraight(t *testing.T) {
	s := skin.New(64, 64)
	// Canal de cor maior que o alfa: em image.RGBA pré-multiplicado isto seria
	// escurecido/clampado; a textura Bedrock é alfa direto.
	fillSkin(&s, 8, 8, 8, color.RGBA{R: 220, G: 100, B: 40, A: 128})
	res := renderAvatar(s)
	got := rgbaAt(decodeAvatar(t, res), 0, 0)
	if got.R != 220 || got.G != 100 || got.B != 40 || got.A != 128 {
		t.Fatalf("alfa parcial perdeu canais diretos: %v", got)
	}
}

func TestSkinExportOnlyTextureAndModel(t *testing.T) {
	s := skin.New(256, 256)
	fillSkin(&s, 0, 0, 16, color.RGBA{R: 200, G: 10, B: 20, A: 128})
	s.Model = []byte(`{"format_version":"1.21.0","minecraft:geometry":[]}`)
	s.FullID = "must-not-export"
	s.Cape = skin.NewCape(64, 32)
	export := buildSkinExport(s)
	img, err := png.Decode(bytes.NewReader(export.TexturePNG))
	if err != nil {
		t.Fatal(err)
	}
	if img.Bounds().Dx() != 256 || img.Bounds().Dy() != 256 {
		t.Fatalf("textura exportada não é 256x256: %v", img.Bounds())
	}
	if got := rgbaAt(img, 0, 0); got.R != 200 || got.A != 128 {
		t.Fatalf("textura exportada perdeu alfa: %v", got)
	}
	if !bytes.Equal(export.ModelJSON, s.Model) {
		t.Fatal("modelo exportado não é o JSON recebido")
	}
	if strings.Contains(string(export.ModelJSON), "must-not-export") {
		t.Fatal("vazou identificador de login")
	}
	if export.Info.PixelBytes != 262144 || export.Info.ModelBytes != len(s.Model) {
		t.Fatalf("diagnóstico incompleto: %+v", export.Info)
	}
}
