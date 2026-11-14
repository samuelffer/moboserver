#!/usr/bin/env python3
"""Gera os ícones de launcher a partir do PNG combinado do repositório.

Uso:
    python3 scripts/make-launcher-icons.py [origem]

Origem padrão: arquivos/icon_moboserver.png (1254x1254, RGBA).

O que é gerado, sem recortar cantos da arte original:
  * mipmap-*/ic_launcher.png          ícone legado quadrado (o launcher aplica a máscara)
  * mipmap-*/ic_launcher_round.png    variação circular para launchers antigos
  * mipmap-*/ic_launcher_foreground.png  camada do ícone adaptativo (108 dp, arte em 66%)
  * drawable-nodpi/ic_splash.png      ícone da tela de abertura

Não usa Pillow de propósito: o ambiente de build só precisa de Python.
"""

import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = ROOT / "arquivos" / "icon_moboserver.png"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
LEGACY_SIZE_DP = 48
ADAPTIVE_SIZE_DP = 108
ADAPTIVE_ART_RATIO = 66 / 108
SPLASH_SIZE = 512


def decode_png(path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path} não é PNG")
    pos = 8
    width = height = None
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if depth != 8 or color_type not in (2, 6) or interlace != 0:
                raise SystemExit("PNG precisa ser 8 bits, RGB ou RGBA, sem entrelaçamento")
        elif kind == b"IDAT":
            idat += chunk
        pos += 12 + length

    raw = zlib.decompress(bytes(idat))
    channels = 4 if color_type == 6 else 3
    stride = width * channels
    rows = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        if filter_type == 1:
            for x in range(channels, stride):
                line[x] = (line[x] + line[x - channels]) & 0xFF
        elif filter_type == 2:
            for x in range(stride):
                line[x] = (line[x] + previous[x]) & 0xFF
        elif filter_type == 3:
            for x in range(stride):
                left = line[x - channels] if x >= channels else 0
                line[x] = (line[x] + ((left + previous[x]) >> 1)) & 0xFF
        elif filter_type == 4:
            for x in range(stride):
                left = line[x - channels] if x >= channels else 0
                up = previous[x]
                up_left = previous[x - channels] if x >= channels else 0
                p = left + up - up_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
                predictor = left if (pa <= pb and pa <= pc) else (up if pb <= pc else up_left)
                line[x] = (line[x] + predictor) & 0xFF
        rows.append(line)
        previous = line

    pixels = []
    for y in range(height):
        row = []
        line = rows[y]
        for x in range(width):
            i = x * channels
            if channels == 4:
                row.append((line[i], line[i + 1], line[i + 2], line[i + 3]))
            else:
                row.append((line[i], line[i + 1], line[i + 2], 255))
        pixels.append(row)
    return width, height, pixels


def write_png(path, pixels):
    height = len(pixels)
    width = len(pixels[0])
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for pixel in row:
            raw.extend(pixel)

    def chunk(kind, payload):
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def resize(src, sw, sh, dw, dh):
    """Redimensiona por média de área, em alfa pré-multiplicado (sem halo escuro)."""
    out = []
    for y in range(dh):
        y0 = y * sh // dh
        y1 = max(y0 + 1, (y + 1) * sh // dh)
        row = []
        for x in range(dw):
            x0 = x * sw // dw
            x1 = max(x0 + 1, (x + 1) * sw // dw)
            r = g = b = a = 0
            count = 0
            for sy in range(y0, y1):
                line = src[sy]
                for sx in range(x0, x1):
                    pr, pg, pb, pa = line[sx]
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
                    count += 1
            if a == 0:
                row.append((0, 0, 0, 0))
            else:
                row.append((r // a, g // a, b // a, a // count))
        out.append(row)
    return out


def scaled_canvas(src, sw, sh, canvas, art_ratio):
    """Coloca a arte centralizada num canvas transparente com a proporção pedida."""
    inner = max(1, int(round(canvas * art_ratio)))
    art = resize(src, sw, sh, inner, inner)
    pad = (canvas - inner) // 2
    blank = [(0, 0, 0, 0)] * canvas
    out = [list(blank) for _ in range(canvas)]
    for y in range(inner):
        for x in range(inner):
            out[pad + y][pad + x] = art[y][x]
    return out


def circle_mask(pixels):
    size = len(pixels)
    center = (size - 1) / 2
    radius = size / 2
    out = []
    for y in range(size):
        row = []
        for x in range(size):
            dx = x - center
            dy = y - center
            if dx * dx + dy * dy <= radius * radius:
                row.append(pixels[y][x])
            else:
                row.append((0, 0, 0, 0))
        out.append(row)
    return out


def main():
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SOURCE
    if not source.is_file():
        raise SystemExit(f"ícone de origem não encontrado: {source}")
    width, height, pixels = decode_png(source)
    print(f"origem: {source.relative_to(ROOT)} ({width}x{height})")

    for density, scale in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        legacy = int(LEGACY_SIZE_DP * scale)
        square = resize(pixels, width, height, legacy, legacy)
        write_png(folder / "ic_launcher.png", square)
        write_png(folder / "ic_launcher_round.png", circle_mask(square))

        adaptive = int(ADAPTIVE_SIZE_DP * scale)
        write_png(folder / "ic_launcher_foreground.png", scaled_canvas(pixels, width, height, adaptive, ADAPTIVE_ART_RATIO))
        print(f"  {density}: {legacy}px legado, {adaptive}px adaptativo")

    write_png(RES / "drawable-nodpi" / "ic_splash.png", scaled_canvas(pixels, width, height, SPLASH_SIZE, 0.8))
    print(f"  splash: {SPLASH_SIZE}px")


if __name__ == "__main__":
    main()
