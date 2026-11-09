# Desktop verification record

Verified on Windows x64 on 2026-11-09. The current desktop client/server source and build paths are Rust; the Java application, tests, Maven descriptor, dependency bootstrap and legacy comparison/build tools have been removed. Android is outside the current scope.

## Completed checks

- Formatting and strict Clippy passed.
- The final headless configuration passed all 23 applicable tests without desktop/audio/neural features.
- Native package installation passed SHA-256 checks and an idempotent second update; replaced files are backed up by the native updater.
- 34 default-feature Rust tests passed, including actual DAC inference, encrypted vault/legacy archive migration, SRP and certificate binding, HTTPS/SOCKS routing, messages/images, roles, friends/calls, bans, key rotation, voice/video relays and bridge access checks.
- Production egui controls were driven against isolated vaults and a real local HTTPS server. Checks cover vault create/unlock validation and retry, immediate lock with pending-work isolation, draft/editor cleanup, profile/audio/theme persistence, text/image sending, reconnects, connection preferences, server overview/channel/role saves, rotation, bans/unbans and listener shutdown. PTT tests cover Space/V/B, release, focus loss and typing suppression.
- Three browser suites passed with synthetic media: DAC voice playback/floor transfer/cleanup; native-driver/browser voice interoperability; screen/webcam encode/encrypt/relay/decrypt/render, opt-in receiving, simultaneous streams, bitrate updates and failure cleanup.
- Live Java/Rust compatibility passed in both directions before Java removal. Both implementations authenticated, verified signatures and decrypted the other implementation's messages. Fixed synthetic fixtures remain in Rust regression tests.
- Native Windows UI inspection confirmed the server console starts/stops, text sending works, the system file picker selects an image, image staging/sending/rendering works, audio preferences render, missing-microphone errors are visible, leaving voice clears the session and locking returns to the vault screen.
- The three final release executables built successfully. Client/server command-line help and native vault-screen startup passed. The bundled codec reported ready at 16 kHz with hop 320. The package contains no Java source, JARs or JVM.

The checks fixed retained editor/draft state and stale worker results after locking, stale errors after successful vault retries, and voice cleanup when switching communities. These fixes have regression coverage.

## Environment limits

The physical-device test was explicitly attempted. CPAL reported no input devices and one output, `Remote Audio`; the test failed with `No microphone available`. It is opt-in and remains ignored by the default suite. The user accepted automated audio checks with this limitation recorded. Native worker failure/stop handling and synthetic two-way encode/decode/playback are verified; physical microphone/speaker fidelity is not claimed.

macOS/Linux native execution and real camera/display devices were not available in this Windows session. Browser media sources were synthetic. These are remaining environment checks, not reported as passes. Previously generated legacy release packages and dependency caches are preserved; the current Rust build and native package do not use them.

## Coverage

Fresh LLVM coverage across all targets: 74.79% functions and 83.75% lines. The report includes closures, entrypoints and test/example code. It is not 100% coverage and does not establish that every function or OS callback has been exercised. Native CLI/window entrypoints were also checked outside the instrumented test run.

| Rust source | Functions % | Lines % |
| --- | ---: | ---: |
| address.rs | 66.67 | 97.30 |
| audio.rs | 23.81 | 25.48 |
| auth.rs | 100.00 | 100.00 |
| bin/client.rs | 0.00 | 0.00 |
| bin/server.rs | 0.00 | 0.00 |
| client.rs | 96.43 | 96.27 |
| codec.rs | 100.00 | 97.09 |
| crypto.rs | 85.71 | 96.43 |
| desktop.rs | 62.59 | 69.60 |
| http.rs | 100.00 | 88.84 |
| images.rs | 100.00 | 91.55 |
| lib.rs | 87.50 | 96.00 |
| limiter.rs | 100.00 | 100.00 |
| main.rs | 16.67 | 21.35 |
| models.rs | 100.00 | 100.00 |
| server.rs | 85.71 | 94.98 |
| tls.rs | 71.43 | 93.28 |
| vault.rs | 100.00 | 97.14 |
| video.rs | 94.12 | 97.18 |
| voice.rs | 56.52 | 89.33 |

Interactive report: `target/coverage/html/index.html`. JSON: `target/coverage.json`. Reproduce with `scripts/test.ps1 -Coverage -Browser`. The headless configuration uses `cargo test --locked --no-default-features --all-targets`; the real device check uses `cargo test --locked --test desktop_devices -- --ignored --nocapture`. See [build instructions](RUST-BUILD.md) and [migration details](RUST-MIGRATION.md).
