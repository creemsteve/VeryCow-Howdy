# Security model

VCH 0.1.2 is a working early build with a custom protocol. It has not had an independent security audit.

## Protected storage

The client vault contains the profile's ML-DSA private key, cached server password proofs, server addresses and certificate pins, current/previous channel keys and revisions, received message envelopes and preferences. The server vault contains its TLS private key, password verifier, current and previous channel keys, accounts, profile pictures, channel settings, ban lists and encrypted messages. Both vaults require a decryption password when opened.

Vault format: `VCHVAULT1 || salt[16] || nonce[12] || AES-256-GCM ciphertext-and-tag`. The header is authenticated as additional data. Argon2id parameters are fixed at 65,536 KiB, three iterations and one lane, producing a 256-bit key. Writes use a fresh nonce and a temporary encrypted file followed by replacement. The old file is retained until replacement succeeds. A lock file prevents two processes from overwriting one vault. No plaintext chat journal, attachment cache or key backup is created by the application. ImageIO disk caching is disabled. Audio samples and DAC tokens are processed in memory.

AES-256 offers approximately a 128-bit generic quantum-search security margin. This does not strengthen a weak decryption password. Endpoint malware, OS swap, crash dumps, compromised browsers and screenshots are outside the application's encryption boundary. Java strings and GUI buffers cannot all be reliably zeroed in memory.

## Server authentication and accounts

Every server has a stable UUID and TLS certificate. The client accepts a bare IP address or domain and internally uses HTTPS on port 585. The first connection uses a narrowly scoped TLS bootstrap context solely for the password exchange. It checks certificate expiry and rejects a certificate change during the exchange. It does not send the account public key, identity signature, profile or any session token during that bootstrap.

Mutual password authentication uses Bouncy Castle's SRP-6a implementation with the fixed RFC 5054 3072-bit group and SHA-256. The SRP identity includes the protocol version, server UUID and SHA-256 fingerprint of the certificate observed in the actual TLS handshake. The client never relies on a fingerprint merely asserted in a JSON response. Client/server evidence must verify before the certificate is trusted. Certificate substitution changes the SRP identity and breaks the password proof, including a relay between two different certificates. The registration request then uses a fresh, normally pinned TLS connection and an HMAC derived from the authenticated SRP session key.

After successful authentication the client saves the certificate pin in its encrypted vault. Reconnects enforce that saved pin, in addition to verifying the password exchange. A changed established certificate fails closed; automatic password authentication is not permission to silently replace a saved pin. Self-signed certificates are supported: certificate pinning replaces public-CA hostname validation after bootstrap. TLS and finite-field SRP are classical algorithms, not claimed to be post-quantum. Application content encryption and identity signatures retain the separate protections described below.

On first join, possession of the shared join password is the server trust anchor. Anyone who knows that password can establish an impostor server if they also redirect a first-time client's connection. Established saved pins protect subsequent connections from a different certificate. Sharing the password only with intended members and safeguarding the hosting environment remain necessary.

The identity key is ML-DSA-65. An account identifier is SHA-256 over a versioned context, server UUID and encoded public key. The server issues a random 256-bit nonce, an algorithm name and a work factor. The client solves a fresh SHA-256 leading-zero-bit puzzle bound to the account hash. The signed registration transcript binds the server, observed certificate, nonce, account public key and work solution. The accompanying HMAC uses the authenticated SRP session key, not a reusable password proof. The password material used inside SRP is the existing PBKDF2-HMAC-SHA256-derived join key (310,000 iterations), allowing saved accounts and credentials to migrate without storing plaintext passwords or resetting the join password. The server's vault still holds this password-equivalent derived key; protect the server vault accordingly.

Each SRP challenge and post-authentication registration context is single-use and bound to the direct source IP. Challenges expire after two minutes; registration must finish within thirty seconds of password authentication. Wrong passwords and malformed SRP values consume the attempt. Session tokens are random 256-bit values, kept in memory, expire after eight hours and are bound to the source IP. Reconnect after an IP change.

Before reading an initial join body, the server reserves one attempt per IP per ten seconds. Successful and failed password checks renew the cooldown for that same attempt. Unauthorized direct authentication/login requests cannot bypass the admission gate. A new admission replaces older unfinished challenges, and an admission ticket prevents a stalled old request from later creating an extra challenge. Malformed initial requests consume the slot. HTTP 429 replies include `Retry-After` and a matching JSON retry delay. Other IPs have independent slots, while clients behind one NAT share a slot. Authenticated chat traffic is excluded from this gate. Cooldowns use a monotonic in-memory clock; restarting the server resets the rate-limit window.

The registration hash is a proof-of-work challenge and account identity binding, not a password hash used in place of authentication. Knowing an account hash does not let someone use that account. A shared join password authorizes membership; access lists provide channel restrictions. The JSON protocol is an application-layer SRP exchange, not a TLS-SRP cipher suite. Implementation references: [Bouncy Castle SRP client API](https://downloads.bouncycastle.org/lts-java/docs/bccore-lts8on-2.73.11-javadoc/org/bouncycastle/crypto/agreement/srp/SRP6Client.html) and [RFC 5054 group definitions](https://www.rfc-editor.org/rfc/rfc5054).

## Server-managed channel encryption

The server generates an independent random 256-bit key for each text and voice channel. Keys are stored inside its encrypted vault. After successful password authentication and account registration, the session may obtain keys over certificate-pinned HTTPS through polling or the authenticated key endpoint. The unauthenticated challenge and password-proof endpoints never return channel keys. Each response filters keys by the account's current channel access. Voice heartbeats deliver only the current voice channel's key set.

Because the server creates, stores and distributes these keys, its operator can decrypt channel content. This design is not end-to-end confidential against the server. Normal routing still forwards/stores encrypted payloads and does not decrypt chat content. Compromise of an unlocked server or its vault password can expose current and managed historical content. Key delivery inherits the classical TLS transport properties described above; local vault encryption retains its separate AES-256 protection.

Each payload is compressed independently with DEFLATE and then encrypted with AES-256-GCM using a new 96-bit nonce. AAD binds server, channel, sender, message UUID, content kind and key revision. ML-DSA signs the envelope, including ciphertext, timestamp and key revision. Client identity hashes are recomputed from public keys before accepting roster entries, and message signatures are verified before decryption. Decompression has explicit output limits. Legacy revision-zero envelopes keep their original signature/AAD format so saved pre-update history remains readable.

Channel access-list changes rotate that channel's key. Account/IP bans and join-password changes rotate all active channel keys. Bans/password changes revoke sessions; removed users cannot fetch new keys. Valid users refresh during polling or voice heartbeats. A text send prepared before a rotation is rejected by revision, refreshed and retried once. Voice receivers reject obsolete revisions. The server GUI also provides manual rotation. Existing clients retain old keys for their archive; newly authorized users receive the server's managed historical keys for their permitted channels. New authorization therefore includes access to managed earlier channel history.

There is no forward secrecy, double ratchet or automatic decentralized group agreement. A former member retains old keys and received content, and a currently authorized member can deliberately share new keys. Server rotation does not remotely erase devices or old backups. The server cannot recover pre-update manually shared keys that never reached it. Clients that already held those keys preserve them locally when accepting managed keys. An unavailable legacy key cannot be synthesized for a fresh client.

The server also sees network addresses, public identities, display names, bios, avatars, channel membership, timing, message sizes and traffic volume. Profile information is server-side metadata, protected by TLS in transit and the server vault at rest.

The server can withhold/reorder history and lie about metadata. Signatures detect forged content, but the application does not provide a globally consistent append-only transcript or rollback-resistant log. Repeated signed message IDs are deduplicated. Voice frames enforce freshness and an in-memory replay window. Traffic analysis and denial of service are not eliminated.

## Access and attachment limits

Account-hash and exact-IP bans are checked before API actions and revoke sessions. Channel access is checked on polling, sending and signaling. Voice floor leases expire unless refreshed, and conforming clients stop microphone transmission when they lose the floor. A banned or modified client can retain data and old keys; bans are not remote erasure.

The normal client accepts still PNG/JPEG/WebP source images, re-encodes them to WebP, strips original file metadata, and applies byte/dimension limits. It offers no video or arbitrary-file attachment feature. The relay restricts declared message kinds and ciphertext sizes but does not inspect decrypted payloads. A modified authorized client could conceal other content inside an allowed envelope; the UI restrictions are not server-side content scanning.

Use the server directly for IP bans. A conventional reverse proxy makes every connection appear to originate from the proxy; forwarded-IP headers are intentionally not trusted.

## Operational limits

The server retains the newest 10,000 message envelopes; each client preserves its received history until its vault is removed. The current server serializes state changes and encrypted snapshots. It is intended for small communities, not high-scale production. Sessions, voice presence and pending ICE/SDP signals are ephemeral. Reconnect after a server restart. Lost vault passwords and lost identity keys are not recoverable through the server.
