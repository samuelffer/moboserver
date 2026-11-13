#!/usr/bin/env python3
"""Deixa um binário Go compilado para linux/arm64 aceitável para o Bionic.

Por que isso existe
-------------------
O app executa o servidor de dentro de `nativeLibraryDir`, e o kernel só executa
o arquivo se o Bionic (o linker do Android) aceitar o ELF. Um binário Go
compilado com `GOOS=linux CGO_ENABLED=0 -buildmode=pie` reprova em dois pontos:

1. **PT_INTERP**: aponta para `/lib/ld-linux-aarch64.so.1` (loader do glibc),
   que não existe no Android. O `execve` falha com ENOENT — a mensagem do app é
   "Cannot run program ...: error=2, No such file or directory".
   → reescrevemos para `/system/bin/linker64`.

2. **PT_TLS**: o linker do Go grava alinhamento 8 (`ctxt.Arch.RegSize`), mas o
   Bionic exige >= 64 no ARM64 e aborta com SIGABRT (código 134):
   "executable's TLS segment is underaligned: alignment is 8, needs to be at
   least 64 for ARM64 Bionic".
   → subimos o `p_align` para 64. É seguro: sem cgo o runtime do Go nunca lê
   esse segmento (`load_g` retorna direto quando `iscgo == 0`).

`GOOS=android` resolveria os dois de uma vez, mas o grafo de dependências do
servidor não compila para android (`github.com/wlynxg/anet: invalid reference to
net.zoneCache`).

O binário continua sendo Go puro: sem DT_NEEDED e apenas com relocações
R_AARCH64_RELATIVE, então o linker do Android só aplica o deslocamento de carga
e salta para o entry point.

Uso: patch-android-elf.py <arquivo.so>
"""

import struct
import sys

ANDROID_INTERP = b"/system/bin/linker64\x00"
TLS_ALIGN = 64

PT_LOAD = 1
PT_DYNAMIC = 2
PT_INTERP = 3
PT_TLS = 7

EM_AARCH64 = 183


class Elf:
    def __init__(self, path: str) -> None:
        self.path = path
        with open(path, "rb") as handle:
            self.data = bytearray(handle.read())

        if self.data[:4] != b"\x7fELF" or self.data[4] != 2 or self.data[5] != 1:
            raise SystemExit("nao e um ELF64 little-endian")

        e_type, e_machine = struct.unpack_from("<HH", self.data, 16)
        if e_type != 3:
            raise SystemExit(f"esperado PIE (e_type=3), veio e_type={e_type}")
        if e_machine != EM_AARCH64:
            raise SystemExit(f"esperado aarch64 (e_machine=183), veio {e_machine}")

        self.phoff, = struct.unpack_from("<Q", self.data, 32)
        self.phentsize, self.phnum = struct.unpack_from("<HH", self.data, 54)

    def phdrs(self):
        for index in range(self.phnum):
            offset = self.phoff + index * self.phentsize
            p_type, p_flags = struct.unpack_from("<II", self.data, offset)
            p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from(
                "<QQQQQQ", self.data, offset + 8
            )
            yield {
                "index": index,
                "header": offset,
                "type": p_type,
                "offset": p_offset,
                "vaddr": p_vaddr,
                "filesz": p_filesz,
                "memsz": p_memsz,
                "align": p_align,
            }

    def fix_interp(self) -> None:
        for phdr in self.phdrs():
            if phdr["type"] != PT_INTERP:
                continue
            start = phdr["offset"]
            size = phdr["filesz"]
            if size < len(ANDROID_INTERP):
                raise SystemExit(f"PT_INTERP de {size} bytes, insuficiente")
            original = bytes(self.data[start:start + size]).split(b"\x00")[0].decode(
                errors="replace"
            )
            if original == "/system/bin/linker64":
                print("PT_INTERP ja aponta para /system/bin/linker64")
                return
            self.data[start:start + size] = ANDROID_INTERP + b"\x00" * (size - len(ANDROID_INTERP))
            print(f"PT_INTERP: {original} -> /system/bin/linker64")

    def fix_tls_alignment(self) -> None:
        for phdr in self.phdrs():
            if phdr["type"] != PT_TLS:
                continue
            if phdr["align"] >= TLS_ALIGN:
                print(f"PT_TLS ja alinhado ({phdr['align']})")
                return
            if (phdr["vaddr"] - phdr["offset"]) % TLS_ALIGN != 0:
                raise SystemExit(
                    "PT_TLS nao e congruente com o alinhamento de 64 bytes "
                    f"(vaddr={phdr['vaddr']} offset={phdr['offset']})"
                )
            # p_align fica no fim do Elf64_Phdr (campo de 8 bytes).
            struct.pack_into("<Q", self.data, phdr["header"] + 48, TLS_ALIGN)
            print(f"PT_TLS: alinhamento {phdr['align']} -> {TLS_ALIGN} (memsz={phdr['memsz']})")

    def audit(self) -> None:
        loads = [p for p in self.phdrs() if p["type"] == PT_LOAD]
        dyn = next((p for p in self.phdrs() if p["type"] == PT_DYNAMIC), None)
        needed = 0
        if dyn is not None:
            start = dyn["offset"]
            end = start + dyn["filesz"]
            for offset in range(start, end, 16):
                tag, _ = struct.unpack_from("<qQ", self.data, offset)
                if tag == 0:
                    break
                if tag == 1:
                    needed += 1
        if needed:
            raise SystemExit("binario com DT_NEEDED: nao ha como resolver no Android")
        min_align = min(p["align"] for p in loads)
        if min_align < 16384:
            raise SystemExit(f"PT_LOAD com alinhamento {min_align}: Android 15+ pede 16 KB")
        print(f"auditoria ok: {len(loads)} PT_LOAD (align {min_align}), 0 DT_NEEDED")

    def save(self) -> None:
        with open(self.path, "wb") as handle:
            handle.write(self.data)


def main() -> int:
    if len(sys.argv) != 2:
        print("uso: patch-android-elf.py <arquivo.so>", file=sys.stderr)
        return 2
    elf = Elf(sys.argv[1])
    elf.fix_interp()
    elf.fix_tls_alignment()
    elf.audit()
    elf.save()
    print(f"ok: {sys.argv[1]} pronto para o Android")
    return 0


if __name__ == "__main__":
    sys.exit(main())
