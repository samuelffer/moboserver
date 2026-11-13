#!/usr/bin/env python3
"""Aponta o interpretador ELF de um PIE Linux para o linker do Android.

Por que isso existe
-------------------
O Android executa binários apenas se eles forem PIE e, quando são dinâmicos,
o kernel chama o interpretador apontado em PT_INTERP. Um binário Go compilado
com `GOOS=linux` grava `/lib/ld-linux-aarch64.so.1` (loader do glibc), que
**não existe** no Android: o `execve` falha com ENOENT ("error=2, No such file
or directory"), mesmo com o arquivo presente e executável.

`GOOS=android` resolveria isso, mas o grafo de dependências do servidor não
compila para android (`github.com/wlynxg/anet: invalid reference to
net.zoneCache`). A alternativa usada aqui: compilar para linux e reescrever o
PT_INTERP para `/system/bin/linker64`, que existe em qualquer Android.

O binário é Go puro (`CGO_ENABLED=0`): não tem DT_NEEDED e usa apenas
relocações RELATIVE, então o linker do Android só aplica o deslocamento de
carga e salta para o entry point — não é preciso nenhuma biblioteca do sistema.

Uso: patch-interp.py <arquivo.so>
"""

import struct
import sys

TARGET = b"/system/bin/linker64\x00"

PT_INTERP = 3


def main() -> int:
    if len(sys.argv) != 2:
        print("uso: patch-interp.py <arquivo.so>", file=sys.stderr)
        return 2
    path = sys.argv[1]

    with open(path, "rb") as handle:
        data = bytearray(handle.read())

    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        print("nao e um ELF64 little-endian", file=sys.stderr)
        return 1

    e_type, e_machine = struct.unpack_from("<HH", data, 16)
    e_phoff, = struct.unpack_from("<Q", data, 32)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)

    if e_type != 3 or e_machine != 183:
        print(f"esperado PIE aarch64 (e_type=3, e_machine=183), veio {e_type}/{e_machine}",
              file=sys.stderr)
        return 1

    patched = False
    for index in range(e_phnum):
        offset = e_phoff + index * e_phentsize
        p_type, = struct.unpack_from("<I", data, offset)
        if p_type != PT_INTERP:
            continue
        p_offset, = struct.unpack_from("<Q", data, offset + 8)
        p_filesz, = struct.unpack_from("<Q", data, offset + 32)
        if p_filesz < len(TARGET):
            print(f"PT_INTERP com {p_filesz} bytes, insuficiente para {TARGET!r}",
                  file=sys.stderr)
            return 1
        original = bytes(data[p_offset:p_offset + p_filesz]).split(b"\x00")[0]
        data[p_offset:p_offset + p_filesz] = TARGET + b"\x00" * (p_filesz - len(TARGET))
        print(f"PT_INTERP: {original.decode(errors='replace')} -> /system/bin/linker64")
        patched = True

    if not patched:
        print("aviso: o binario nao tem PT_INTERP (ja e estatico)", file=sys.stderr)

    with open(path, "wb") as handle:
        handle.write(data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
