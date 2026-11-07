package chat.vch;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;

public final class Crypto {
    private static final Provider BC = new BouncyCastleProvider();
    static { Security.addProvider(BC); }
    private static final SecureRandom RNG = new SecureRandom();
    public static byte[] random(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }
    public static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    public static byte[] unb64(String b) { return Base64.getDecoder().decode(b); }
    public static byte[] utf(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    public static byte[] hash(byte[] b) { try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new IllegalStateException(e); } }
    public static String hex(byte[] b) { StringBuilder s=new StringBuilder(b.length*2);for(byte v:b)s.append(Character.forDigit((v>>>4)&15,16)).append(Character.forDigit(v&15,16));return s.toString(); }
    public static byte[] derive(char[] password, byte[] salt) {
        Argon2BytesGenerator a = new Argon2BytesGenerator();
        a.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withSalt(salt)
            .withMemoryAsKB(65536).withIterations(3).withParallelism(1).build());
        byte[] key = new byte[32]; a.generateBytes(password, key); return key;
    }
    public static byte[] passwordKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec p = new PBEKeySpec(password, salt, 310000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(p).getEncoded(); }
        finally { p.clearPassword(); }
    }
    public static byte[] hmac(byte[] key, byte[] b) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(b);
    }
    public static byte[] seal(byte[] key, byte[] plain, byte[] aad) throws Exception {
        byte[] nonce = random(12); Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce)); c.updateAAD(aad);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(nonce); out.write(c.doFinal(plain)); return out.toByteArray();
    }
    public static byte[] open(byte[] key, byte[] sealed, byte[] aad) throws Exception {
        if (sealed.length < 28) throw new GeneralSecurityException("Invalid encrypted data");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(sealed, 12)));
        c.updateAAD(aad); return c.doFinal(sealed, 12, sealed.length - 12);
    }
    public static byte[] compress(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(out)) { z.write(plain); } return out.toByteArray();
    }
    public static byte[] decompress(byte[] compressed, int max) throws IOException {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] b = Io.read(in,max + 1); if (b.length > max) throw new IOException("Decompressed data exceeds limit"); return b;
        }
    }
    public static KeyPair identity() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("ML-DSA", BC); g.initialize(MLDSAParameterSpec.ml_dsa_65); return g.generateKeyPair();
    }
    public static PrivateKey privateKey(String b) throws Exception { return KeyFactory.getInstance("ML-DSA", BC).generatePrivate(new PKCS8EncodedKeySpec(unb64(b))); }
    public static PublicKey publicKey(String b) throws Exception { return KeyFactory.getInstance("ML-DSA", BC).generatePublic(new X509EncodedKeySpec(unb64(b))); }
    public static String sign(PrivateKey k, byte[] bytes) throws Exception {
        Signature s = Signature.getInstance("ML-DSA", BC); s.initSign(k); s.update(bytes); return b64(s.sign());
    }
    public static boolean verify(String publicKey, byte[] bytes, String sig) throws Exception {
        Signature s = Signature.getInstance("ML-DSA", BC); s.initVerify(publicKey(publicKey)); s.update(bytes); return s.verify(unb64(sig));
    }
    public static String account(String server, String publicKey) { return hex(hash(utf("VCH/account/1|" + server + "|" + publicKey))); }
    public static boolean proof(String prefix, String answer, int bits) {
        if (answer.length() > 24 || bits < 8 || bits > 24) return false;
        byte[] h = hash(utf(prefix + "|" + answer));
        for (int i = 0; i < bits; i++) if ((h[i / 8] & (1 << (7 - i % 8))) != 0) return false;
        return true;
    }
    public static String solve(String prefix, int bits) throws Exception {
        if (bits < 8 || bits > 22) throw new GeneralSecurityException("Unsupported registration work factor");
        for (long i = 0; ; i++) { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); String x = Long.toString(i); if (proof(prefix, x, bits)) return x; }
    }
}
