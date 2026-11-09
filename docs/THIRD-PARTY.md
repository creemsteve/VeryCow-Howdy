# Third-party components

The Rust dependency versions and declared licenses are listed in [RUST-DEPENDENCIES.md](RUST-DEPENDENCIES.md) and pinned by `Cargo.lock`. Cargo registry packages retain their upstream license texts.

| Additional runtime asset | Version | License / upstream |
| --- | --- | --- |
| ONNX Runtime native library | 1.23.2 | MIT; https://github.com/microsoft/onnxruntime |
| DAC 16 kHz model | Model tag 0.0.5; export package 1.0.0 | MIT; https://github.com/descriptinc/descript-audio-codec |
| IBM Plex Sans and IBM Plex Mono fonts | Bundled fonts | SIL Open Font License; `src/main/resources/fonts/OFL.txt`; https://github.com/IBM/plex |

`licenses/` includes model/runtime notices and retained historical notices for older releases. No JAR or JRE is included in the current native package. Python packages used for model export and development checks are development tools, not application runtime dependencies.

Native video links source-built OpenH264 (BSD-2-Clause), XCap (Apache-2.0) and Nokhwa (Apache-2.0). Their notices are included in `licenses/`; the Cargo lockfile pins the bindings and native source. Video encoding/decoding is software AVC. No browser assets or JavaScript runtime are packaged.
