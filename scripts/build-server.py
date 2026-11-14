#!/usr/bin/env python3
"""Compila o servidor Go com identidade de fonte controlada.

Uso: scripts/build-server.py [output] [--host]
Sem --host compila linux/arm64 PIE para Android e aplica patch-android-elf.py.
A versão vem de version.properties; revisão e hash de fonte vêm do Git.
"""
from __future__ import annotations
import hashlib, os, pathlib, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

def run(cmd, **kw):
    return subprocess.check_output(cmd, cwd=ROOT, text=True, **kw).strip()

def main() -> int:
    args = [a for a in sys.argv[1:] if a != "--host"]
    host = "--host" in sys.argv[1:]
    out = (pathlib.Path(args[0]).resolve() if args else ROOT / ("server/mobosrv" if host else "android/app/src/main/jniLibs/arm64-v8a/libmobosrv.so"))
    props = {}
    for line in (ROOT / "version.properties").read_text().splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1); props[k.strip()] = v.strip()
    version = props["versionName"]
    revision = run(["git", "rev-parse", "HEAD"])
    dirty = bool(run(["git", "status", "--porcelain", "--untracked-files=no"]))
    # Hash determinístico dos inputs de fonte rastreados que afetam o produto.
    paths = run(["git", "ls-files", "-z", "--", "server", "android/app/src/main", "android/app/build.gradle.kts", "version.properties", "scripts/build-server.py", "scripts/patch-android-elf.py"]).split("\0")
    h = hashlib.sha256()
    for rel in sorted(p for p in paths if p):
        data = (ROOT / rel).read_bytes()
        h.update(rel.encode()); h.update(b"\0"); h.update(hashlib.sha256(data).digest())
    source_hash = h.hexdigest()
    if dirty:
        revision += "-dirty"
    ldflags = f"-s -w -X main.appVersion={version} -X main.buildRevision={revision} -X main.buildSourceHash={source_hash}"
    env = os.environ.copy()
    env.setdefault("CGO_ENABLED", "0")
    if not host:
        env["GOOS"] = "linux"; env["GOARCH"] = "arm64"
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["go", "build", "-trimpath", "-ldflags", ldflags]
    if not host:
        cmd += ["-buildmode=pie"]
    cmd += ["-o", str(out), "."]
    subprocess.check_call(cmd, cwd=ROOT / "server", env=env)
    if not host:
        subprocess.check_call([sys.executable, str(ROOT / "scripts/patch-android-elf.py"), str(out)], cwd=ROOT)
    print(f"built {out} version={version} revision={revision} source={source_hash}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
