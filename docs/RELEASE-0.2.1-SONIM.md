# VCH Flip 0.2.1 — Sonim XP3plus 5G preview

A new native keypad client backed by the current Rust VCH core, with encrypted chat and offline archives, text/images, profiles, friends and calls, Tor-only connections, DAC push-to-talk voice, camera/screen video, incoming video opt-in, audio controls, role hierarchy and server/channel management.

Designed for the XP3plus 5G's AOSP 14 and 240×320 display. No touchscreen, Google Play Services, old Android APK or legacy Java protocol code is required. The signed universal APK contains ARM64 and x86-64 native libraries and bundled DAC models. Package: `chat.vch.flip`; Android 10+; version code 201.

Verification: 37 Rust tests passed; host JNI smoke tests passed; Android debug/release builds and lint passed; signature, native alignment, ZIP alignment and bundled models checked. One existing host-device test is intentionally ignored. Android instrumentation was compiled but could not run because the local emulator remained offline and no handset was connected. Camera/audio devices, screen sharing, physical key mapping, lid events, small-screen layout and Sonim firmware installation remain unverified. **This is a preview, not a claim of physical handset certification.**

Download `VCH-Flip-0.2.1-release.apk` and its SHA-256 checksum. Install using the handset's permitted APK flow, managed installation or authorized `adb install -r`. Allow microphone/camera/notification access when requested. Grant screen-capture consent only when starting a share. The roughly 318 MB APK includes the existing neural voice models; first voice use requires additional app-private storage for their extracted copy.

See [Android documentation](ANDROID.md) for controls, every feature, build/signing instructions, lifecycle behavior and the full verification record. App signing material stays local and is not included in the release or repository.
