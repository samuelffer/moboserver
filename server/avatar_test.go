package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
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
