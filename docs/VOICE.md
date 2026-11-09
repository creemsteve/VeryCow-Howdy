# Voice implementation

VCH uses the 16 kHz DAC model, release `0.0.5`, exported into a shared pair of ONNX graphs. The Rust desktop client runs inference locally through native ONNX Runtime. The model has 12 codebooks, a 320-sample hop and packed 10-bit codebook indices, giving selectable token rates from 500 to 6,000 bits/s. Envelope signatures, compression, AES-GCM, base64, HTTPS and polling add transport overhead.

## Desktop audio

The native controls stay inside the egui conversation window while browsing text channels. CPAL opens the selected microphone and playback device. Captured mono audio is resampled to 16 kHz, gain-adjusted and encoded in memory. The microphone stream is started only after a PTT lease is granted. Capture and sending are gated by the current lease and the pressed state; releasing PTT or losing focus clears the pressed state, and the audio worker pauses capture. Text entry does not activate keyboard PTT. Leaving closes devices and the voice session. Device preference changes apply when rejoining.

Each speaker sends one signed, encrypted copy to the authenticated `/api/voice` HTTPS relay. Receivers verify the ML-DSA signature, server/channel/author binding, current key revision, speaking floor, timestamp and replay window before decrypting and decoding. The server holds channel keys and can decrypt content; normal relay operation forwards ciphertext. Audio is never saved in chat history or the vault.

The server accepts media only from a joined participant holding the current speaking lease. Each channel buffers at most six frames for at most three seconds. Polling cursors skip already delivered frames, the sender's own frames, obsolete key revisions and audio buffered before a new participant joined. Floor release, leaving, channel changes, access revocation, password changes and shutdown clear the applicable live state. Queues are bounded, so overloaded processing drops live blocks rather than accumulating unlimited latency.

Community voice and private friend calls use this same server relay. No peer media connections, participant IP exchange, ICE, STUN, TURN or separate media port are needed. Tor-only mode uses the saved local SOCKS5 route for all control and media requests, with remote hostname resolution and no direct retry. Tor runs separately. Voice membership defaults to 16 and can be configured up to 32.

This is block-based voice: capture, inference, transport, polling and playback all contribute latency. TCP stalls can exceed a speaking lease, and Tor can add delay. Real microphone/speaker behavior, acoustic quality and public Tor latency still need device and network testing.

Voice and video are native Rust. There is no browser companion, JavaScript resource, local web server or browser token. The desktop owns the call session directly.

## Screen sharing and webcams

Choose **Video / screen share** during a desktop voice channel or friend call to open the native in-app panel. A participant can publish one screen and one webcam simultaneously. Select a display or camera, then explicitly start sharing. Display sharing captures the entire selected display. No capture starts when opening the panel. Each source has an independent 50–1,000 kbps control that can change during the call. Incoming streams list metadata first; **Receive** starts unchecked for every stream. Unchecking stops requesting its payloads and clears its decoder/preview. This is a bandwidth preference, not an additional authorization boundary.

Rust uses XCap for display capture, Nokhwa for native camera access, and source-built OpenH264 for software AVC baseline encoding/decoding. No codec downloads or external media processes run at application startup. Frames are letterboxed to 640×360 for webcam and 960×540 for displays, at up to eight frames per second. Independent keyframes let receivers subscribe immediately and tolerate dropped frames. The existing AVC packet framing remains compatible with the native Android client; VP8 is no longer emitted or decoded by desktop. Hardware video acceleration is not implemented in this native backend.

The native panel includes source selectors, live local previews, per-stream bitrate controls, incoming receive checkboxes and Stop all sharing. Closing the panel keeps sharing active, with a visible sharing indicator in the call bar. Stop, leave, lock and worker/relay failure release capture devices and clear previews. Camera capture and encoding run on a worker, keeping the UI responsive.

`/api/video` requires authenticated, current voice membership and channel view/connect permission. Streams are bound to owner, channel and source. Video does not require the PTT floor. The server enforces a sliding one-second encrypted-payload budget of `kbps × 125` bytes per stream; envelope/base64/TLS overhead is additional. Out-of-budget uploads receive HTTP 429.

Frames use the existing compressed AES-256-GCM/ML-DSA envelope with `video/<stream UUID>` as the signed kind. The decrypted payload contains a four-byte big-endian JSON-header length, a UTF-8 header (`codec`, `width`, `height`, `timestamp`) and encoded keyframe bytes. Receivers verify bindings and signatures, enforce the current key revision, bound decompression to 125,000 bytes, reject replay IDs and validate codec/dimensions. Each stream retains at most 12 frames for 1.5 seconds and expires after ten seconds without publication activity. Stop, leave, channel/access changes, bans, password changes and shutdown revoke the applicable streams. Video is never persisted in history or vault snapshots.

## Verification

`scripts/test.ps1` covers codec packing and real DAC inference, relay authorization, floor exclusion/transfer, signatures, freshness/replay, key revisions, bounded buffering, expiry, cleanup and local HTTPS/SOCKS routing. `tests/native_video.rs` additionally exercises real AVC encode/decode, malformed packets, two concurrent synthetic camera/display sources through the encrypted relay, opt-in receiving, live bitrate changes, unsubscribe clearing and capture cleanup after session failure. These checks do not establish physical camera or display capture quality.

Upstreams: [DAC](https://github.com/descriptinc/descript-audio-codec), [OpenH264 Rust bindings](https://github.com/ralfbiedert/openh264-rs), [XCap](https://github.com/nashaofu/xcap), [Nokhwa](https://github.com/l1npengtul/nokhwa).
