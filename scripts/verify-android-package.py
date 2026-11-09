"""Verify the packaged native libraries and exact local DAC model inputs."""
import hashlib
import json
import pathlib
import struct
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
apk = pathlib.Path(sys.argv[1])
report = {"apk": apk.name, "sha256": hashlib.file_digest(apk.open("rb"), "sha256").hexdigest(), "bytes": apk.stat().st_size, "libraries": {}, "models": {}}
with zipfile.ZipFile(apk) as archive:
    report["build"] = json.loads(archive.read("assets/vch-build.json"))
    for abi in ("arm64-v8a", "x86_64"):
        for library in ("libvch_android.so", "libonnxruntime.so", "libonnxruntime4j_jni.so"):
            name = f"lib/{abi}/{library}"
            binary = archive.read(name)
            if binary[:5] != b"\x7fELF\x02":
                raise ValueError(f"Invalid ELF64 library: {name}")
            machine = struct.unpack_from("<H", binary, 18)[0]
            if machine != (183 if abi == "arm64-v8a" else 62):
                raise ValueError(f"Wrong machine architecture: {name}")
            offset = struct.unpack_from("<Q", binary, 32)[0]
            size, count = struct.unpack_from("<HH", binary, 54)
            alignments = [struct.unpack_from("<Q", binary, offset + i * size + 48)[0] for i in range(count) if struct.unpack_from("<I", binary, offset + i * size)[0] == 1]
            if not alignments or min(alignments) < 16384:
                raise ValueError(f"Native library lacks 16 KB load alignment: {name}")
            report["libraries"][name] = {"loadAlignment": min(alignments), "sha256": hashlib.sha256(binary).hexdigest()}
    for name in ("dac.json", "dac-encoder.onnx", "dac-decoder.onnx"):
        digest = hashlib.sha256(archive.read("assets/" + name)).hexdigest()
        with (root / "build/models" / name).open("rb") as source:
            if digest != hashlib.file_digest(source, "sha256").hexdigest():
                raise ValueError(f"Packaged model differs from build input: {name}")
        report["models"][name] = digest
    for name in ("assets/LICENSE", "assets/licenses/ONNXRuntime-MIT.txt", "assets/licenses/Gson-Apache.txt", "assets/licenses/OFL.txt"):
        if name not in archive.namelist():
            raise ValueError(f"Missing license: {name}")
    info = "META-INF/version-control-info.textproto"
    if info in archive.namelist():
        report["versionControl"] = archive.read(info).decode("utf-8")
output = root / "build/android-verification/package.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(f"Verified {apk.name}: ELF architectures/alignment, exact DAC model hashes, and bundled notices")
print(f"SHA-256: {report['sha256']}")
