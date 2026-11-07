package chat.vch;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.security.*;
import java.security.cert.*;
import java.math.BigInteger;
import java.util.Date;
import java.io.*;
import javax.net.ssl.*;

public final class Tls {
    public static String create() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC"); gen.initialize(256); KeyPair k = gen.generateKeyPair();
        X500Name name = new X500Name("CN=VCH Server"); long now = System.currentTimeMillis();
        var builder = new JcaX509v3CertificateBuilder(name, new BigInteger(128, new SecureRandom()),
            new Date(now - 60000), new Date(now + 10L * 365 * 86400000), name, k.getPublic());
        var cert = new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(k.getPrivate())));
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, null);
        store.setKeyEntry("vch", k.getPrivate(), new char[0], new java.security.cert.Certificate[]{cert});
        ByteArrayOutputStream out = new ByteArrayOutputStream(); store.store(out, new char[0]); return Crypto.b64(out.toByteArray());
    }
    public static KeyStore store(String encoded) throws Exception { KeyStore s = KeyStore.getInstance("PKCS12"); s.load(new ByteArrayInputStream(Crypto.unb64(encoded)), new char[0]); return s; }
    public static String fingerprint(String store) throws Exception { return Crypto.hex(Crypto.hash(store(store).getCertificate("vch").getEncoded())); }
    public static SSLContext server(String encoded) throws Exception {
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(store(encoded), new char[0]);
        SSLContext c = SSLContext.getInstance("TLSv1.3"); c.init(km.getKeyManagers(), null, null); return c;
    }
    public static SSLContext pinned(String expected) throws Exception {
        if (!expected.matches("[0-9a-fA-F]{64}")) throw new GeneralSecurityException("Enter the 64-character server certificate fingerprint");
        SSLContext c = SSLContext.getInstance("TLSv1.3");
        c.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Client certificates unsupported"); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain.length == 0 || !Crypto.hex(Crypto.hash(chain[0].getEncoded())).equalsIgnoreCase(expected)) throw new CertificateException("Server fingerprint changed");
                chain[0].checkValidity();
            }
        }}, null); return c;
    }
}
