# VCH 0.1.1

- Join with only an IP address/domain and server password on desktop and Android.
- Default server port: 585. HTTPS is selected internally.
- Certificate discovery is authenticated by a certificate-bound SRP-6a password exchange before saving trust or disclosing the account identity. Established pins remain enforced on reconnect.
- One join attempt per direct source IP per ten seconds. Malformed attempts, parallel attempts and delayed request bodies cannot create extra password attempts. Ordinary text and voice traffic are unaffected.
- Saved configurations using the previous default port migrate once. Identities, cached join credentials, certificate pins, channels and saved messages are retained.

Install the matching 0.1.1 client and server. Older handshake versions are rejected. Keep each Windows EXE inside its application folder. The Android APK remains debug-signed for Android 10+; physical-device testing is pending. Server hosts must permit binding/listening on TCP 585; some Unix hosts require low-port binding permission.
