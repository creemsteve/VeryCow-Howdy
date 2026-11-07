# VCH 0.1.2

- Servers generate separate random keys for every text and voice channel, store them in the encrypted server vault, and provide them automatically after successful password login.
- Channel access lists apply to key delivery. Wrong passwords, unauthenticated requests, banned accounts and banned IPs cannot obtain current keys.
- Manual client key entry is removed from Windows, portable desktop and Android interfaces.
- Channel access changes, bans and server-password changes rotate keys. Valid clients refresh automatically; managed historical keys keep saved messages readable.
- The server console includes **Rotate channel key** for an administrator-selected rotation.

This changes the trust model: the server holds the encryption keys and can decrypt channel content. It is server-managed encryption, not secrecy from the server operator. Old manually configured keys remain in clients' encrypted archives; they are not silently uploaded to the server.

Update both applications to 0.1.2. The default port remains 585 and the ten-second per-IP join limit remains active. Keep each Windows EXE with its application folder. Android remains a debug build requiring Android 10+.
