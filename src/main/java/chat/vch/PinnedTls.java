package chat.vch;

import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

public final class PinnedTls {
    /** Only used for the SRP exchange; no profile, identity proof or session is sent before authentication. */
    public static final class Bootstrap {
        private String fingerprint;
        private final SSLContext context;
        public Bootstrap(String expected) throws Exception {
            context=SSLContext.getInstance("TLSv1.3");
            context.init(null,new TrustManager[]{new X509TrustManager(){
                public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException{throw new CertificateException("Client certificates unsupported");}
                public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{
                    if(chain.length==0)throw new CertificateException("Missing server certificate");
                    chain[0].checkValidity();
                    synchronized(Bootstrap.this){
                        String observed=Crypto.hex(Crypto.hash(chain[0].getEncoded()));
                        if(expected!=null&&!expected.isEmpty()&&!expected.equalsIgnoreCase(observed))throw new CertificateException("Saved server certificate changed");
                        if(fingerprint!=null&&!fingerprint.equals(observed))throw new CertificateException("Certificate changed during password handshake");
                        fingerprint=observed;
                    }
                }
            }},null);
        }
        public SSLSocketFactory factory(){return context.getSocketFactory();}
        public synchronized String fingerprint() throws GeneralSecurityException {
            if(fingerprint==null)throw new GeneralSecurityException("Server certificate was not observed");
            return fingerprint;
        }
    }
    public static SSLContext create(String expected)throws Exception{
        if(!expected.matches("[0-9a-fA-F]{64}"))throw new GeneralSecurityException("Enter the 64-character server certificate fingerprint");
        SSLContext c=SSLContext.getInstance("TLSv1.3");c.init(null,new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException{throw new CertificateException("Client certificates unsupported");}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{
                if(chain.length==0||!Crypto.hex(Crypto.hash(chain[0].getEncoded())).equalsIgnoreCase(expected))throw new CertificateException("Server fingerprint changed");chain[0].checkValidity();
            }
        }},null);return c;
    }
}
