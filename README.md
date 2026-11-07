# VCH

Private communities with a Java client, a separate Java server console, encrypted local archives, text/image channels, and push-to-talk voice.

## Open the applications

The current build is in `releases/v0.1.2/`:

| Platform | Open | Status |
| --- | --- | --- |
| Windows x64 client | `VCH-Client/VCH-Client.exe` | Built; bundled Java and DAC models |
| Windows x64 server GUI | `VCH-Server/VCH-Server.exe` | Built; bundled Java |
| macOS Intel / Apple Silicon | `VCH-Portable/Client.command` or `Server.command` | Portable Java build; platform testing pending |
| Linux x64 / ARM64 | `VCH-Portable/client.sh` or `server.sh` | Portable Java build; platform testing pending |
| Android / Sonim AOSP | `VCH-Android-AOSP-debug.apk` | Debug APK compiled; Android 10+; physical-device testing pending |

Keep each Windows EXE inside its entire application folder. No separate Java installation is needed for the Windows builds. The portable build needs Java 17+, a desktop environment for the GUIs, and an up-to-date browser for voice. On macOS/Linux, use `sh client.sh` or `sh server.sh` from the portable folder; executable permission is not required with that command. Native macOS/Linux installers have not been produced on this Windows host.

The Android package uses Android APIs and the system WebView, with no Google Play Services dependency. Voice requires a WebRTC-capable system WebView, microphone permission, sufficient memory for the DAC model, and a reachable peer network. Sonim devices with Android versions below 10 are not supported by this build. Hardware side-key mapping is in **Audio → Map hardware PTT key**; manufacturer-reserved keys may require OEM configuration.

## Start a community

1. Open the server application. Create and confirm a vault password of at least 12 characters. This password unlocks the server's saved configuration, accounts, bans and ciphertext history.
2. In **Overview**, set the server name and listen address, then save. `0.0.0.0` listens on all IPv4 interfaces; the default server port is **585**. The port can still be customized for advanced deployments.
3. Set a separate **join password**, then start the server. Share just the IP address or domain and join password with intended members. Clients authenticate and save the server certificate automatically using the password; nobody needs to copy a fingerprint.
4. Use **Server customization** to add independent text and voice channels. Text channels support slow mode and account-hash access lists. Voice channels support access lists, maximum participants and a DAC token-rate target of 500–6,000 bits/s.
5. In **Accounts & bans**, ban full account hashes or exact IP addresses. Bans revoke current sessions and survive restarts. IP addresses come from the direct socket, not forwarded headers.

For public hosting, run the server on your own central host, point a domain at it, and allow TCP port 585 through your network. If you deliberately choose another port, allow that port instead. Linux/macOS may require low-port binding permission because 585 is below 1024; grant that permission to the server process through your host's service configuration. No cloud host or public domain has been provisioned. The server process must stay running. Each server maintains its own accounts, access rules, password and history.

There is also a terminal server entry point, `chat.vch.ServerDaemon`, for hosts without a desktop. The portable `server-headless.sh` prompts for the vault password without putting it in command-line arguments. Stop it before opening the same vault in the server GUI; concurrent vault access is rejected.

## Join and chat

1. Open the client and create a local vault password of at least 12 characters. Every application launch requires this decryption password.
2. Customize your display name, bio and picture in **Profile**. The same local identity/profile is used across connected servers; each server sees a different server-specific account hash.
3. Choose **Join a server** and enter an address such as `192.168.1.20` or `chat.example.com`, plus the server password. HTTPS and port 585 are added internally. An SRP-6a password exchange binds the certificate actually presented by the server to the password proof; only after the server proves its password knowledge does the client pin the certificate and register its identity. Registration also solves the server's fresh SHA-256 work challenge and proves ownership of your ML-DSA identity. Optional explicit ports and pasted HTTPS addresses are still accepted for advanced deployments.
4. Select a channel and start chatting. The server generates a separate random encryption key for each text and voice channel and provides it automatically after a correct password login. Access lists still apply: users receive keys only for channels they are allowed to access. There is no manual key entry.
5. Send text or a still image. Images are converted to WebP locally and reduced to at most **100,000 bytes**. Profile pictures are reduced to at most **10,000 bytes** and saved on the server. Video/file attachments are disabled.
6. For voice, select a voice channel and choose **Join voice**. Its key is set up automatically. On desktop, the Java app opens a loopback browser panel; on Android, an embedded panel opens. Choose devices, enable audio and hold the push-to-talk button or configured key. Releasing the key or losing panel focus mutes transmission.

Saved servers, channel keys, identity, preferences and message history are inside the encrypted vault. Use **Reconnect** after unlocking to resume a server connection; multiple servers can remain connected together. Saved messages remain readable while offline. A changed join password requires a fresh join using the new password. The server rotates keys when channel access changes, an account/IP is banned, or its join password changes. Clients refresh keys automatically and retain older keys in their encrypted vault for saved history. Administrators can also use **Server customization → Rotate channel key**.

The server stores current and previous channel keys in its password-encrypted vault. As the key provider, the server can decrypt channel content. This is server-managed encryption, not end-to-end secrecy from the server operator. Normal chat routing continues to store and forward encrypted payloads.

Each direct source IP may start only **one join attempt every ten seconds**, whether the previous attempt succeeded, failed or was malformed. Server responses include the remaining retry delay. The password-proof and registration steps belong to that same attempt; ordinary messages, polling and voice are unaffected. Clients behind the same public IP share this delay. There is no automatic retry loop. Rate-limit windows are in memory and restart with the server.

Update both applications to **0.1.2** together for automatic key delivery. Saved 0.1.0 configurations using the old default 8443 migrate once to 585. Existing identities, established certificate pins, cached credentials, channel keys and history are preserved; deliberate custom ports remain supported. After an established pin has been saved, a changed certificate is rejected rather than silently replacing that trust. Older manually configured keys remain on clients that already held them, so those clients can still read their archived messages. The server cannot distribute a pre-update manual key it never received; fresh clients may be unable to read that legacy content.

On Windows/macOS/Linux the default vault locations are `~/.vch/client/data.vault` and `~/.vch/server/data.vault`. Android uses private application storage. `-Dvch.data=/path` selects an alternate desktop data root. Back up the encrypted vault while the application is closed. There is no password reset or recovery service for a lost vault password.

## Voice and security details

Voice uses DAC neural compression over **WebRTC data channels**, with application-layer AES-256-GCM encryption and ML-DSA signatures in addition to WebRTC transport encryption. It does not negotiate DAC as a standard WebRTC audio-track codec. The graph runs locally through ONNX Runtime; Python is only used by developers to export models. See [voice behavior and limits](docs/VOICE.md).

Local storage uses **Argon2id** (64 MiB, three passes) and **AES-256-GCM**, with fresh nonces and atomic encrypted writes. AES-256 provides a symmetric post-quantum security margin; ML-DSA-65 provides post-quantum identity signatures. This is not a claim that the whole networking stack uses post-quantum TLS. Read the [security model](docs/SECURITY.md) for metadata exposure, key distribution, membership changes and protocol limitations.

## Build and verify

For a fresh Git checkout, restore the pinned Java libraries with Python 3.8+ (standard library only):

```sh
python scripts/fetch-dependencies.py
```

Each download is checked against `docs/dependencies.sha256` before it replaces a local library. `python scripts/fetch-dependencies.py --check` verifies existing files without downloading anything.

Windows, with a Java 17 JDK on PATH:

```powershell
.\scripts\build.ps1
.\scripts\test.ps1
.\scripts\build.ps1 -Package
.\scripts\build-android.ps1
.\scripts\package-portable.ps1
```

`-Package` creates runtime-bundled Windows application folders. Choose a new `-Destination` when a target folder already exists. Downloaded dependency JARs live in `lib/` and are excluded from Git; restore them with the command above. The Android build requires Gradle 8.9 and Android SDK 35/build-tools 34.0.0; the helper script recognizes the project-local tools used for this build. Android Studio can also open the `android/` folder.

The repository tracks application/test source, build scripts, product icons, dependency checksums, documentation and upstream license notices. `.gitignore` excludes build outputs, release packages, downloaded libraries/tools/model weights, Python environments, local vaults and lock files, signing material, credentials and machine-specific configuration. Keep those generated or private files outside commits. `.gitattributes` standardizes source line endings while preserving binary assets.

For a fresh source checkout without model files, create an isolated Python environment, install `descript-audio-codec==1.0.0`, PyTorch and ONNX, then run `python voice/export_onnx.py`. Exported models go into `build/models/`; desktop packaging and Android include them automatically. The exported weights total about 298 MB and account for most of the client download size.

Verification includes host-only joins on actual port 585, SRP mutual password proofs and certificate substitution rejection, forged server evidence, real retry headers, concurrent and stalled-request cooldown cases, saved-data migration, encrypted text/image delivery, bans and voice floor control. Java DAC and browser WebRTC checks use synthetic audio. Android compilation/lint is also checked. Live hardware audio, internet NAT traversal, macOS/Linux execution and physical Sonim behavior still need platform testing. The APK is debug-signed for testing, not a store release.

See [third-party components](docs/THIRD-PARTY.md) for upstream licenses and implementation references.
