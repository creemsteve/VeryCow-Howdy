# Voice implementation

VCH uses the 16 kHz DAC model, release `0.0.5`, exported into a shared pair of ONNX graphs. The Rust desktop client runs inference locally through native ONNX Runtime. The model has 12 codebooks, a 320-sample hop and packed 10-bit codebook indices, giving selectable token rates from 500 to 6,000 bits/s. Envelope signatures, compression, AES-GCM, base64, HTTPS and polling add transport overhead.

## Desktop audio

The native controls stay inside the egui conversation window while browsing text channels. CPAL opens the selected microphone and playback device. Captured mono audio is resampled to 16 kHz, gain-adjusted and encoded in memory. The microphone stream is started only after a PTT lease is granted. Capture and sending are gated by the current lease and the pressed state; releasing PTT or losing focus clears the pressed state, and the audio worker pauses capture. Text entry does not activate keyboard PTT. Leaving closes devices and the voice session. Device preference changes apply when rejoining.

Each speaker sends one signed, encrypted copy to the authenticated `/api/voice` HTTPS relay. Receivers verify the ML-DSA signature, server/channel/author binding, current key revision, speaking floor, timestamp and replay window before decrypting and decoding. The server holds channel keys and can decrypt content; normal relay operation forwards ciphertext. Audio is never saved in chat history or the vault.

The server accepts media only from a joined participant holding the current speaking lease. Each channel buffers at most six frames for at most three seconds. Polling cursors skip already delivered frames, the sender's own frames, obsolete key revisions and audio buffered before a new participant joined. Floor release, leaving, channel changes, access revocation, password changes and shutdown clear the applicable live state. Queues are bounded, so overloaded processing drops live blocks rather than accumulating unlimited latency.

Community voice and private friend calls use this same server relay. No peer media connections, participant IP exchange, ICE, STUN, TURN or separate media port are needed. Tor-only mode uses the saved local SOCKS5 route for all control and media requests, with remote hostname resolution and no direct retry. Tor runs separately. Voice membership defaults to 16 and can be configured up to 32.

This is block-based voice: capture, inference, transport, polling and playback all contribute latency. TCP stalls can exceed a speaking lease, and Tor can add delay. Real microphone/speaker behavior, acoustic quality and public Tor latency still need device and network testing.

The browser voice page is retained for desktop browser/native-driver interoperability tests. The token-protected loopback `VoiceBridge` also serves the desktop video companion. It enforces exact loopback Host/Origin checks and a per-session token, supplies no CORS access, and closes with the voice call.

## Screen sharing and webcams

Choose **Video / screen share** during a desktop voice channel or friend call to open the browser companion. A participant can publish one screen and one webcam simultaneously. Starting capture requires a click and browser consent. Each source has an independent 50–1,000 kbps control that can change during the call. Incoming streams list metadata first; **Receive** starts unchecked for every stream. Unchecking stops requesting its payloads and clears its decoder/canvas. This is a bandwidth preference, not an additional authorization boundary.

WebCodecs requests hardware acceleration, trying AVC baseline with Annex B framing and VP8, then the available encoder if the hardware preference is unsupported. Hardware acceleration is a browser/platform hint. No video codec binaries are distributed by VCH. Initial dimensions are 640×360 for webcam and 960×540 for screen sharing, with letterboxing and up to eight frames per second. Independent keyframes let receivers subscribe immediately and tolerate dropped frames.

`/api/video` requires authenticated, current voice membership and channel view/connect permission. Streams are bound to owner, channel and source. Video does not require the PTT floor. The server enforces a sliding one-second encrypted-payload budget of `kbps × 125` bytes per stream; envelope/base64/TLS overhead is additional. Out-of-budget uploads receive HTTP 429.

Frames use the existing compressed AES-256-GCM/ML-DSA envelope with `video/<stream UUID>` as the signed kind. The decrypted payload contains a four-byte big-endian JSON-header length, a UTF-8 header (`codec`, `width`, `height`, `timestamp`) and encoded keyframe bytes. Receivers verify bindings and signatures, enforce the current key revision, bound decompression to 125,000 bytes, reject replay IDs and validate codec/dimensions. Each stream retains at most 12 frames for 1.5 seconds and expires after ten seconds without publication activity. Stop, leave, channel/access changes, bans, password changes and shutdown revoke the applicable streams. Video is never persisted in history or vault snapshots.

## Verification

`scripts/test.ps1` covers codec packing and real inference, relay authorization, floor exclusion/transfer, signatures, freshness/replay, key revisions, bounded buffering, expiry, cleanup and local HTTPS/SOCKS routing. `scripts/test.ps1 -Browser` additionally runs browser-to-browser voice, native-driver/browser voice interoperability and webcam/screen sharing with synthetic media. The video suite checks encode–encrypt–relay–decrypt–render, simultaneous sources, live bitrate changes, receive toggles and capture cleanup. These checks do not establish physical GPU selection or real audio-device quality.

Upstream model: [DAC repository](https://github.com/descriptinc/descript-audio-codec). Browser codec API: [WebCodecs](https://www.w3.org/TR/webcodecs/).
