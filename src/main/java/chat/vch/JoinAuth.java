package chat.vch;

import org.bouncycastle.crypto.agreement.srp.*;
import org.bouncycastle.crypto.digests.SHA256Digest;
import java.math.BigInteger;
import java.security.*;

/** SRP-6a with a fixed 3072-bit group; the identity binds the actual TLS certificate. */
public final class JoinAuth {
    public static final String PROTOCOL = "VCH-SRP6a-SHA256-3072-v2";
    private JoinAuth() {}
    public static byte[] identity(String serverId, String fingerprint) {
        return Crypto.utf(PROTOCOL + "|" + serverId + "|" + fingerprint);
    }
    public static SRP6Client client() {
        SRP6Client client = new SRP6Client();
        client.init(SRP6StandardGroups.rfc5054_3072, new SHA256Digest(), new SecureRandom());
        return client;
    }
    public static SRP6Server server(byte[] salt, byte[] identity, byte[] passwordKey) {
        SRP6VerifierGenerator generator = new SRP6VerifierGenerator();
        generator.init(SRP6StandardGroups.rfc5054_3072, new SHA256Digest());
        SRP6Server server = new SRP6Server();
        server.init(SRP6StandardGroups.rfc5054_3072, generator.generateVerifier(salt, identity, passwordKey), new SHA256Digest(), new SecureRandom());
        return server;
    }
    public static BigInteger number(String hex) throws GeneralSecurityException {
        if (hex == null || !hex.matches("[0-9a-fA-F]{1,768}")) throw new GeneralSecurityException("Invalid password handshake");
        BigInteger value = new BigInteger(hex, 16);
        if (value.signum() <= 0 || value.compareTo(SRP6StandardGroups.rfc5054_3072.getN()) >= 0) throw new GeneralSecurityException("Invalid password handshake");
        return value;
    }
    public static byte[] sessionKey(BigInteger key) { return Crypto.hash(key.toByteArray()); }
    public static String transcript(String server, String fingerprint, String nonce, String publicKey, String solution) {
        return "VCH/login/2|" + server + "|" + fingerprint + "|" + nonce + "|" + publicKey + "|" + solution;
    }
}
