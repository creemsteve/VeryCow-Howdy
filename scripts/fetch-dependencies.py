#!/usr/bin/env python3
"""Restore pinned Java libraries without committing downloaded binaries to Git."""
from pathlib import Path
import argparse
import hashlib
import re
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PACKAGES = {
    "bcpkix-jdk18on-1.83.jar": "org/bouncycastle/bcpkix-jdk18on/1.83/bcpkix-jdk18on-1.83.jar",
    "bcprov-jdk18on-1.83.jar": "org/bouncycastle/bcprov-jdk18on/1.83/bcprov-jdk18on-1.83.jar",
    "bcutil-jdk18on-1.83.jar": "org/bouncycastle/bcutil-jdk18on/1.83/bcutil-jdk18on-1.83.jar",
    "gson-2.11.0.jar": "com/google/code/gson/gson/2.11.0/gson-2.11.0.jar",
    "kotlin-stdlib-2.4.0.jar": "org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.jar",
    "onnxruntime-1.23.2.jar": "com/microsoft/onnxruntime/onnxruntime/1.23.2/onnxruntime-1.23.2.jar",
    "webp-imageio-0.11.0.jar": "com/github/usefulness/webp-imageio/0.11.0/webp-imageio-0.11.0.jar",
}


def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest():
    expected = {}
    for line in (ROOT / "docs/dependencies.sha256").read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        digest, name = line.split()
        if not re.fullmatch(r"[0-9a-f]{64}", digest) or name not in PACKAGES or name in expected:
            raise ValueError("Invalid dependency checksum manifest")
        expected[name] = digest
    if set(expected) != set(PACKAGES):
        raise ValueError("Dependency list and checksum manifest do not match")
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify existing files without downloading anything")
    args = parser.parse_args()
    expected = manifest()
    missing = []
    for name, digest in expected.items():
        destination = ROOT / "lib" / name
        if destination.is_file() and checksum(destination) == digest:
            print(f"Verified {name}")
            continue
        if args.check:
            missing.append(name)
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(".jar.part")
        try:
            request = urllib.request.Request(
                "https://repo.maven.apache.org/maven2/" + PACKAGES[name],
                headers={"User-Agent": "VCH dependency bootstrap"},
            )
            print(f"Downloading {name}")
            with urllib.request.urlopen(request, timeout=120) as source, temporary.open("wb") as output:
                total = 0
                while chunk := source.read(1024 * 1024):
                    total += len(chunk)
                    if total > 200 * 1024 * 1024:
                        raise ValueError("Dependency exceeds download size limit")
                    output.write(chunk)
            if checksum(temporary) != digest:
                raise ValueError(f"Checksum mismatch for {name}; existing library was not replaced")
            temporary.replace(destination)
            print(f"Verified {name}")
        finally:
            temporary.unlink(missing_ok=True)
    if missing:
        raise SystemExit("Missing or mismatched libraries: " + ", ".join(missing))


if __name__ == "__main__":
    main()
