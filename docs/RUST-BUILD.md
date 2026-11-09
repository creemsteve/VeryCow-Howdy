# Build the Rust application

Install the stable Rust toolchain and an OS-native linker/build environment. The lockfile pins dependencies. On Windows the MSVC C++ build tools are required. On Linux, egui's windowing dependencies and ALSA development headers are needed. Native desktop builds must be built/tested on their target OS.

```powershell
./scripts/build.ps1 -Debug
./scripts/test.ps1
./scripts/test.ps1 -Coverage
./scripts/build.ps1 -Package -Destination releases/v0.2.0-rust
```

Equivalent direct commands:

```sh
cargo build --locked --release --bin vch
cargo test --locked --all-targets
cargo fmt --all --check
cargo clippy --locked --all-targets -- -D warnings
cargo run --release -- client
cargo run --release -- server
cargo run --release -- daemon --bind 0.0.0.0 --port 585
```

`--data DIRECTORY` selects a vault directory containing `data.vault`. Default directories remain `~/.vch/client` and `~/.vch/server`. A lost vault password cannot be reset. The daemon prompts for passwords without command-line password arguments. Use `--set-join-password` to change its join password and `--admin FULL_REGISTERED_HASH` to grant administrator access.

The default build includes the desktop UI, system audio and neural codec. A server-only build uses `cargo build --release --no-default-features` and runs `vch daemon`. A source-only test without DAC assets can use `scripts/test.ps1 -AllowMissingModels`; that skips full voice inference/session verification.

Voice needs `dac.json`, `dac-encoder.onnx` and `dac-decoder.onnx` in `build/models`, or a directory selected by `--models`. The existing model-export script produces these files. ONNX Runtime is loaded natively: place the platform runtime library in the model directory or set `ORT_DYLIB_PATH` to its absolute path. On this Windows checkout, `build/models/onnxruntime.dll` is the pinned native ONNX Runtime 1.23.2 library. No JVM or Python process runs in the Rust application. Python is used only for model export and development tooling. Native video uses source-built OpenH264, XCap and Nokhwa; it does not require a browser, JavaScript engine or external capture program. Linux desktop builds also need the capture libraries documented by XCap (XCB, PipeWire/GBM and Clang development packages) and Video4Linux headers.

`vch codec --models DIRECTORY` accepts the existing JSON-lines `init`/`encode`/`decode` protocol on stdin, which is useful for codec tooling and tests.

## Desktop verification

`cargo test --locked --lib desktop::verification` drives the production egui controls against isolated vaults and a real local HTTPS server. It checks vault create/unlock/retry/lock, stale worker isolation, PTT focus/release, messages and images, profiles/audio preferences, theme persistence, reconnects, server editors, rotations, bans and shutdown.

The explicit hardware check is `cargo test --locked --test desktop_devices -- --ignored --nocapture`. It records a short block and plays a quiet test tone. It fails if no microphone/speaker exists; it does not silently report missing hardware as a pass.

For native UI inspection, create an empty directory under `build`, add a `DESKTOP-TEST-ONLY` marker, build `cargo build --locked --example desktop-fixture`, then run `target/debug/examples/desktop-fixture server DIRECTORY` and start the test server in its console. Run the same example with `client DIRECTORY` to open a connected production client UI. These test vaults use public fixture passwords and contain no real user data. The normal product executables always use the regular vault unlock flow.

`scripts/update-apps.ps1 -Source releases/v0.2.0-rust -Destination dist/VCH` installs a native package, verifies copied hashes and backs up replaced files. Close destination apps before updating.
