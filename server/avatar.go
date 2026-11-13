package main

// Miniaturas de cabeca a partir da skin real da sessao Bedrock.
//
// A skin chega em player.Skin() (skin.Skin): Pix em RGBA direto (nao
// pre-multiplicado), Bounds() com as dimensoes e Model/ModelConfig/Persona
// descrevendo a geometria. O recorte segue o layout humanoid padrao:
//
//	64x64  e 64x32 (classico): rosto [8,16)x[8,16), camada externa [40,48)x[8,16)
//	128x128 (layout padrao em escala dupla): rosto [16,32)x[16,32),
//	                                        camada externa [80,96)x[16,32)
//
// A camada externa e composta com "source over", a miniatura e ampliada com
// nearest-neighbor (pixels definidos) e codificada em PNG. Modelos que nao usam
// a geometria humanoid (persona/geometria propria) devolvem
// "prevista indisponivel" com o motivo, em vez de uma imagem errada.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"image"
	"image/color"
	"image/png"
	"strings"

	"github.com/df-mc/dragonfly/server/player/skin"
)

const avatarSize = 64

// avatarResult is what the app receives for one player.
type avatarResult struct {
	Available bool
	PNG       []byte
	Hash      string
	Layout    string
	Reason    string
}

// avatarLayout describes the UV mapping of one skin layout.
type avatarLayout struct {
	name   string
	width  int
	height int
	faceX  int
	faceY  int
	hatX   int
	hatY   int
	region int // size of the 8x8 (or 16x16) region in the texture
	legacy bool
}

var (
	layoutClassic = avatarLayout{name: "64x64", width: 64, height: 64, faceX: 8, faceY: 8, hatX: 40, hatY: 8, region: 8}
	layout64x32   = avatarLayout{name: "64x32", width: 64, height: 32, faceX: 8, faceY: 8, hatX: 40, hatY: 8, region: 8, legacy: true}
	layout128     = avatarLayout{name: "128x128", width: 128, height: 128, faceX: 16, faceY: 16, hatX: 80, hatY: 16, region: 16}
)

// renderAvatar crops the face, composes the outer layer and returns a 64x64 PNG.
func renderAvatar(s skin.Skin) avatarResult {
	bounds := s.Bounds()
	w, h := bounds.Dx(), bounds.Dy()

	// Limites de tamanho antes de alocar ou indexar qualquer coisa.
	if w <= 0 || h <= 0 || w > 256 || h > 256 {
		return avatarResult{Reason: "dimensões de skin inesperadas"}
	}
	if len(s.Pix) != w*h*4 {
		return avatarResult{Reason: "textura da skin com tamanho inconsistente"}
	}
	if s.Persona {
		return avatarResult{Reason: "skin Persona"}
	}
	layout, ok := avatarLayoutFor(w, h)
	if !ok {
		return avatarResult{Reason: "textura fora dos layouts humanoides conhecidos"}
	}
	if reason := unsupportedModel(s.Model); reason != "" {
		return avatarResult{Reason: reason}
	}

	face := make([]color.RGBA, layout.region*layout.region)
	for y := 0; y < layout.region; y++ {
		for x := 0; x < layout.region; x++ {
			face[y*layout.region+x] = pixelAt(s, layout.faceX+x, layout.faceY+y)
		}
	}

	// Camada externa (chapéu/cabelo). Skins antigas 64x32 às vezes carregam alfa
	// opaco numa área que o jogo nunca desenhou; nesse caso o formato compatível
	// é ignorar a camada em vez de pintar um bloco preto sobre o rosto.
	useHat := !(layout.legacy && uniformOpaqueBlack(s, layout.hatX, layout.hatY, layout.region))
	out := image.NewRGBA(image.Rect(0, 0, avatarSize, avatarSize))
	scale := avatarSize / layout.region
	if scale < 1 {
		scale = 1
	}
	for y := 0; y < layout.region; y++ {
		for x := 0; x < layout.region; x++ {
			base := face[y*layout.region+x]
			c := base
			if useHat {
				hat := pixelAt(s, layout.hatX+x, layout.hatY+y)
				c = sourceOver(hat, base)
			}
			for dy := 0; dy < scale; dy++ {
				for dx := 0; dx < scale; dx++ {
					out.SetRGBA(x*scale+dx, y*scale+dy, c)
				}
			}
		}
	}

	var buf bytes.Buffer
	if err := png.Encode(&buf, out); err != nil {
		return avatarResult{Reason: "falha ao codificar a imagem"}
	}
	sum := sha256.Sum256(buf.Bytes())
	return avatarResult{
		Available: true,
		PNG:       buf.Bytes(),
		Hash:      hex.EncodeToString(sum[:])[:16],
		Layout:    layout.name,
	}
}

func avatarLayoutFor(w, h int) (avatarLayout, bool) {
	switch {
	case w == 64 && h == 64:
		return layoutClassic, true
	case w == 64 && h == 32:
		return layout64x32, true
	case w == 128 && h == 128:
		return layout128, true
	}
	return avatarLayout{}, false
}

// unsupportedModel returns a reason when the geometry is not the standard
// humanoid one, for which the fixed UV coordinates above are valid.
func unsupportedModel(model []byte) string {
	if len(model) == 0 {
		return "" // geometria humanoid padrão
	}
	text := strings.ToLower(string(model))
	if strings.Contains(text, "persona") {
		return "geometria Persona"
	}
	if !strings.Contains(text, "geometry.humanoid") {
		return "geometria personalizada"
	}
	return ""
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
