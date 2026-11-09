# VCH

Private communities with a native Rust desktop client, server console and headless server, encrypted local archives, text/image channels, friends, roles, push-to-talk voice, and optional webcam/screen sharing.

The desktop client and server are implemented in Rust. VCH Flip adds a native Android keypad interface over the same current Rust client and media core, targeting the Sonim XP3plus 5G (AOSP 14, 240×320). Its signed preview includes chat/images, profiles, friends/calls, voice/video, Tor, roles and server/channel management. See [Android build, installation and verification](docs/ANDROID.md), [migration details](docs/RUST-MIGRATION.md) and the [desktop verification record](docs/RUST-VERIFICATION.md).

## Build and run

```powershell
.\scripts\build.ps1 -Debug
.\scripts\test.ps1
.\scripts\test.ps1 -Browser
cargo run --release -- client
cargo run --release -- server
cargo run --release -- daemon --bind 0.0.0.0 --port 585
```

Cargo builds the Rust application; no JVM is required to run desktop or headless mode. `--data DIRECTORY` selects a separate vault directory and `--models DIRECTORY` selects DAC models. Default vault paths remain `~/.vch/client/data.vault` and `~/.vch/server/data.vault`. Passwords are prompted for rather than accepted as command-line arguments.

Start the server, create its vault with a password of at least 12 characters, set a separate join password, and start listening. Clients join using an IP address/domain and that join password. HTTPS on port 585 is the default. Saved certificate pins and password proofs authenticate reconnects. Optional Tor-only connections use a separately running trusted SOCKS listener on `127.0.0.1`; they never fall back to a direct connection.

Text and still-image attachments use server-managed per-channel encryption keys. Images are converted to WebP and limited to 100,000 bytes, with up to three per message. Profile/server images are limited to 10,000 bytes. Roles, channel overrides and account allow lists govern access; administrators remain subject to the account allow list. Friends receive participant-only message/call channels. Key rotations retain historical keys for archives.

Voice uses the existing DAC models through native ONNX Runtime, signed encrypted envelopes and the server HTTPS relay. Desktop audio controls stay in the app; configured push-to-talk shortcuts stop on release/focus loss and do not trigger while typing. Video uses the local authenticated browser companion, with independent screen/camera bitrates and receive opt-in. Neither voice nor video uses peer connections. Models/runtime libraries are separate build inputs; see [build instructions](docs/RUST-BUILD.md).

## Verification and compatibility

Rust unit/integration tests cover cryptography and tampering, vault locking/migration, SRP proof/certificate binding, joins/cooldowns, actual pinned HTTPS and SOCKS routing, messages/images, roles, friends/calls, bans, key rotation, voice/video relay rules, actual DAC inference and desktop view rendering. Browser tests use synthetic media for voice and webcam/screen sharing. Coverage is available with `scripts/test.ps1 -Coverage`; untested device/platform paths remain visible.

The existing vault format, identities, certificate pins, account hashes, SRP exchange and message/media wire formats are preserved. Tests consume synthetic Java-generated compatibility fixtures, and live two-way interoperability was verified before removing the Java implementation. Rust tests keep the compatibility fixtures and do not need Java.

The server generates and holds channel keys, so its operator can decrypt channel content. This is server-managed encryption. Local archives retain Argon2id and AES-256-GCM; identities retain ML-DSA-65. The original [security model](docs/SECURITY.md) and [media documentation](docs/VOICE.md) describe the protocol; [Rust migration notes](docs/RUST-MIGRATION.md) identify implementation and verification differences.

Historical release notes and previously generated packages describe older Java releases; the current desktop source build and native desktop package have no Java runtime or build dependency.

VCH Flip is built separately with `scripts/build-android.ps1 -Release`. It uses Java for native Android widgets/devices and JNI for the current Rust core; it does not restore the old Java Android client or change the Rust desktop build. Physical Sonim and Android media-device execution remain unverified; the first Android release is a preview.
