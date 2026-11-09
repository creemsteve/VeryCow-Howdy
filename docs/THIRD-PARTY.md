# Third-party components

| Component | Pinned version | License / upstream |
| --- | --- | --- |
| Gson | 2.11.0 | Apache-2.0; https://github.com/google/gson |
| Bouncy Castle provider, PKIX and utility | 1.83 | MIT; https://www.bouncycastle.org/licence.html |
| WebP ImageIO | 0.11.0 | Apache-2.0; https://github.com/usefulness/webp-imageio |
| libwebp (inside image encoder) | Upstream native build included with WebP ImageIO | BSD-style; https://chromium.googlesource.com/webm/libwebp/+/refs/heads/main/COPYING |
| Kotlin standard library | 2.4.0 | Apache-2.0; https://github.com/JetBrains/kotlin |
| ONNX Runtime Java / Android | 1.23.2 | MIT; https://github.com/microsoft/onnxruntime |
| DAC code and 16 kHz model | Python package 1.0.0, model tag 0.0.5 | MIT; https://github.com/descriptinc/descript-audio-codec |
| Eclipse Temurin runtime (Windows app images) | Local JDK 17.0.20.101 | GPLv2 with Classpath Exception; runtime/legal directories included |

Dependency JARs retain their embedded notices. The Windows app images retain runtime notices. `licenses/` contains additional upstream license copies, including the model license. Build-only tools and Python packages are not part of the Java/Android product runtime. Android's platform WebView is supplied by the operating system. Voice uses the platform audio APIs and the existing HTTPS transport. Historical WebRTC license copies remain in `licenses/` for earlier packages.

The maintained WebP ImageIO fork declares Apple Silicon support in its [upstream README](https://github.com/usefulness/webp-imageio); the bundled native entries cover macOS x64/ARM64, Linux x64/ARM variants and Windows x64/x86. This has been inspected in the JAR but only the Windows x64 encoder has been executed here.

Optional video uses codecs supplied by the desktop browser (WebCodecs) and Android system (MediaCodec/Camera2/MediaProjection). No native video codec binaries are redistributed by VCH; platform codec availability, hardware acceleration and applicable codec licensing depend on that platform.
