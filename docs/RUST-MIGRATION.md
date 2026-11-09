# Rust migration status

The native desktop client, desktop/server console, headless server, protocol, storage, cryptography and media control have Rust implementations in `src/rust`. Cargo is the default desktop build. Browser voice/video presentation and capture use HTML and JavaScript assets. The desktop migration removed the prior Android projects. The subsequent [VCH Flip client](ANDROID.md) supplies a new native keypad UI and JNI adapter over the current Rust client, vault and media core; it does not reinstate the legacy Android implementation.

The Java application/test sources, Maven descriptor, Java dependency bootstrap, live Java comparison oracle and legacy build/package scripts have been removed. Before removal, the live Java/Rust interoperability check passed in both directions, including authentication, signed encrypted messages and decryption. Rust regression tests retain the synthetic compatibility fixtures.

Windows desktop software verification includes native UI inspection and automated production-control workflows. Physical microphone capture could not run because the available session has no input device. macOS/Linux execution was not performed on this Windows host. These limits remain explicit in the [verification record](RUST-VERIFICATION.md).

## Implementation map

| Previous component | Replacement |
| --- | --- |
| Models, Json, Io, MediaRules | `models.rs`, Serde, bounded `http.rs`, `images.rs` |
| Crypto, JoinAuth | `crypto.rs`, `auth.rs` |
| Vault | `vault.rs` |
| ServerAddress, JoinAttemptLimiter | `address.rs`, `limiter.rs` |
| Tls, PinnedTls, TorTransport | `tls.rs`, `http.rs` |
| ChatServer, VideoRelay | `server.rs`, `video.rs` |
| ClientCore | `client.rs` |
| DacCodec | `codec.rs` (native ONNX Runtime; no Python worker) |
| VoiceSession, VoiceBridge, DesktopVoice, VoiceAudio | `voice.rs`, `audio.rs` |
| Launcher, ClientApp, ServerApp, Theme | `main.rs`, `desktop.rs`, egui |
| ServerDaemon | `vch daemon` |

## Compatibility decisions

The vault format remains `VCHVAULT1 || salt[16] || nonce[12] || AES-256-GCM ciphertext/tag`. Argon2id uses 64 MiB, three passes and one lane. Password authentication keeps PBKDF2-HMAC-SHA256 at 310,000 iterations and SRP-6a's RFC 5054 3072-bit group with SHA-256. The Rust SRP implementation reproduces Bouncy Castle's padded evidence hashes and Java signed-BigInteger session-key encoding. Certificate proof uses the certificate observed in the TLS handshake.

ML-DSA-65 public keys remain DER SubjectPublicKeyInfo. Rust reads its seed-form PKCS#8 keys and Bouncy Castle's seed-plus-expanded-key sequence. Accounts retain their server-specific hashes. Revision-zero AAD/signature transcripts remain readable. Managed key rotations preserve historical keys. Java PKCS#12 TLS material loads without replacing the certificate.

The JSON endpoint names, camelCase fields, limits, roles/override order, access lists, friendships, calls, voice leases and relay framing remain compatible. A real Java client has been exercised against the Rust server and a real Rust client against the Java server. Both servers verified and decrypted the other implementation's envelopes.

Images use Rust's lossless WebP encoder with progressive downscaling to satisfy the existing byte limits; resulting dimensions/bytes can differ from Java's lossy encoder. DAC uses the existing ONNX graphs and packed 10-bit `VCD1` wire format. Screen/webcam payloads retain the existing browser relay framing.

## Verification

`scripts/test-rust.ps1` runs formatting, strict Clippy and every Rust test target. `-Browser` additionally tests browser voice, native-driver/browser voice interoperability and webcam/screen sharing. All media in these tests is synthetic. `-Coverage` generates an LLVM report under `target/coverage` without declaring untested OS callbacks covered. A source checkout without model assets can run the smaller suite with `-AllowMissingModels`; that is not full media verification.

The Rust tests cover storage authentication/locking/atomic saves, legacy vaults and archives, crypto known answers and tampering, address parsing, join admission and replay/expiry, roles and hierarchy, key rotation, slow mode, bans, message limits and retention, friends/calls, strict HTTP framing, actual pinned HTTPS, SOCKS remote-name routing and fail-closed behavior, codec packing and real inference, voice floor transfer/replay/cleanup, video receive opt-in and per-stream budgets, bridge Host/Origin/token checks and desktop view rendering with real encrypted messages and images.

## Java removal

The current source tree and build scripts contain no Java implementation or Java build/runtime dependency. Existing encrypted vaults, identities, certificate pins and legacy message archives remain supported by Rust migration tests. Previously generated release packages and historical notes are retained as release history.
