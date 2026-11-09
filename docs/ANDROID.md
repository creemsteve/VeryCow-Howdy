# VCH Flip — Sonim XP3plus 5G preview

VCH Flip is a new native keypad client over the current Rust VCH core. The UI is Java Android Views; JNI delegates authentication, identities, certificate pins, encryption, archives, images, chat, voice and video relay operations to `src/rust`. No old Android APK, Java protocol implementation, WebView UI or Google Play Services is used.

The target is the [Sonim XP3plus 5G](https://www.sonimtech.com/products/phones/xp3plus-5g), specified by Sonim as AOSP 14 with a 240×320 inner display. The APK supports Android 10+ and contains ARM64 handset and x86-64 test libraries. Actual installation and operation on Sonim firmware have not been verified.

## Features and controls

| Current client feature | VCH Flip control |
| --- | --- |
| Encrypted vault, offline archives, saved communities | Create/unlock, Communities, Lock; archive reads work disconnected |
| SRP login, certificate pinning, reconnect/disconnect | Join and community connection controls; current Rust implementation |
| Text, typing, still images, up to three attachments | Write message, Add image; paged archive and individual message/image view |
| Profile name, bio, pronouns, avatar | Your profile; images prepared by Rust to the same byte/dimension limits |
| Friends, requests, removal, private messages, calls | Friends, registered profiles/account hashes, Answer/Decline/Call |
| Tor-only routing | Join or Connection/Tor; separately running trusted local SOCKS listener; no direct fallback |
| DAC push-to-talk voice | Voice channel, native microphone/playback, mapped PTT or hold OK on Hold to talk |
| Microphone/output selection, gains, outgoing bitrate | Audio/Appearance; current Android audio devices; 0–300% and 500–6000 bit/s |
| Camera/screen sharing and incoming video opt-in | Camera/Screen/Receive; independent 50–1000 kbps settings; native AVC encoder and AVC/VP8 decoders |
| Role hierarchy, members, everyone permissions | Manage roles; ordered roles, permission checkboxes and account selection |
| Server name/description/image, channels | Manage server; ordered text/voice channels, slow mode, capacity, bitrate and account allow lists |
| Per-channel role overrides | Role overrides; inherit/allow/deny for view, send and connect |
| Dark/light appearance and call notifications | Audio/Appearance; foreground polling and Android notification permission |

Use ↑/↓ and OK throughout. Left soft key opens Communities and ends media; right soft key goes back. Built-in multi-tap input uses 2–9 for letters, 0 for space, 1 for punctuation, # to cycle abc/ABC/123, and * to delete. Wait 900 ms or move the cursor to commit a repeated-key letter. Numeric fields start in numeric mode. USB/QWERTY input is also accepted.

The vault locks when leaving the app. Exceptions are an explicit image picker (maximum two minutes away) and active screen sharing, which uses an Android foreground service and persistent Stop sharing notification. Closing the flip/screen-off ends media and locks. Push-to-talk stops on key release, window-focus loss, app backgrounding or session close. Image picking and media permission prompts do not authorize unrelated background capture. Incoming calls are polled while the app is unlocked; there is no push service after locking.

## Build and install

Prerequisites: JDK 17, Rust with `aarch64-linux-android` and `x86_64-linux-android` targets, Android SDK platform 35/build-tools 34.0.0, NDK 27.2.12479018, Gradle 8.9, and the current DAC exports from `python voice/export_onnx.py`. SDK and Gradle can be placed under `build/tools` as in the existing workspace, or set `ANDROID_HOME` and `JAVA_HOME`.

```powershell
rustup target add aarch64-linux-android x86_64-linux-android
.\scripts\build-android.ps1
.\scripts\build-android.ps1 -Release -CreateSigningKey  # first local release only
.\scripts\build-android.ps1 -Release                   # reuse the same signing key
.\scripts\test-android-jni.ps1
```

The signed output is `releases/v0.2.1-sonim/VCH-Flip-0.2.1-release.apk` with its SHA-256 file. Preserve `build/android-signing` privately for future compatible updates. Alternatively provide `VCH_ANDROID_KEYSTORE`, `VCH_ANDROID_STORE_PASSWORD`, `VCH_ANDROID_KEY_ALIAS` and, if different, `VCH_ANDROID_KEY_PASSWORD`. Signing keys/passwords, downloaded tools, models and APKs are ignored by Git. Both Cargo lockfiles are checked in. The pinned Gradle wrapper is in `android`.

The APK bundles the existing DAC encoder/decoder models so voice does not require a separate model download or Google services. This accounts for most of the roughly 318 MB package; first voice use copies the models to app-private storage. Rust JNI and ONNX native libraries have 16 KB ELF load alignment.

Install the signed APK through the handset's permitted APK installation flow, device management, or an authorized USB debugging session:

```powershell
adb shell getprop ro.product.cpu.abilist
adb install -r releases/v0.2.1-sonim/VCH-Flip-0.2.1-release.apk
```

`arm64-v8a` must be in the handset ABI list. Package ID: `chat.vch.flip`; display name: VCH Flip; version: 0.2.1 (201). This app has its own developer signature and data directory. It does not replace or import an installed legacy Android app automatically. Existing current-format VCH vault compatibility is supplied by the shared Rust core. Carrier/device installation policies still apply.

## Verification on 2026-11-09

Passed on Windows x64:

- Root Rust formatting and Clippy (`-D warnings`), JNI crate formatting and Clippy.
- All 37 non-ignored root Rust tests, including three mobile adapter tests. One pre-existing test for the host microphone/speakers remains explicitly ignored.
- Real local HTTPS mobile joins, authenticated/encrypted text, image conversion/collections, profile/vault reopening, roles, server settings, disconnect, Tor failure with no fallback, reconnect and server removal.
- Two mobile clients exercising friend requests/acceptance, calls, actual ONNX DAC encode/decode, signed voice relay, replay rejection, video subscription opt-in, encrypted video transport, bitrate configuration, stop and removal with an active session. Video transport tests use synthetic encoded payloads; they do not exercise Android hardware codecs.
- Host JNI smoke tests through the same exported entry points: native session creation, display-state secret filtering, profile persistence, PTT gating, close/stale handles, wrong-password rejection and reopen.
- Signed ARM64/x86-64 release APK assembly; debug/release Android lint; test APK compilation; APK signature and ZIP alignment; native ELF load alignment and bundled model hashes.

Not executed: Android instrumentation and physical Sonim tests. The local emulator reports no installed hypervisor driver; software-emulation attempts remained offline. No Sonim was connected. The framework-only instrumentation APK contains multi-tap input, optional hardware requirements, native vault/JNI and small-screen D-pad focus tests, but compilation is not a runtime pass. Handset layout, Sonim PTT key codes, microphone/speaker routing and performance, camera encoding/decoding, screen-consent/service behavior, lid events and carrier installation must be checked on hardware. Accordingly this release is a preview.

To run the instrumentation on a test device (debug APK and test APK use a separate debug signing identity from the release):

```powershell
.\scripts\build-android.ps1
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w chat.vch.flip.test/chat.vch.flip.FlipInstrumentation
```

Do not install the debug APK over a release installation: their signatures differ. Use a dedicated test device/profile, or back up data before uninstalling an existing application. Instrumentation creates its synthetic vault in app cache and does not erase the user's vault.
