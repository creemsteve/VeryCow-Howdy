use crate::crypto;
use anyhow::{anyhow, bail, Result};
use rustls::{
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime},
    DigitallySignedStruct, SignatureScheme,
};
use std::sync::{Arc, Mutex};

pub fn create() -> Result<String> {
    let certified =
        rcgen::generate_simple_self_signed(vec!["localhost".into(), "VCH Server".into()])?;
    let mut store = p12_keystore::KeyStore::new();
    let cert = p12_keystore::Certificate::from_der(certified.cert.der())?;
    let chain =
        p12_keystore::PrivateKeyChain::new(certified.key_pair.serialize_der(), b"vch", vec![cert]);
    store.add_entry("vch", p12_keystore::KeyStoreEntry::PrivateKeyChain(chain));
    Ok(crypto::b64(&store.writer("").write()?))
}
pub fn material(encoded: &str) -> Result<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>)> {
    let store = p12_keystore::KeyStore::from_pkcs12(&crypto::unb64(encoded)?, "")?;
    let (_, chain) = store
        .private_key_chain()
        .ok_or_else(|| anyhow!("TLS private key missing"))?;
    let certs = chain
        .chain()
        .iter()
        .map(|c| CertificateDer::from(c.as_der().to_vec()))
        .collect();
    Ok((certs, PrivatePkcs8KeyDer::from(chain.key().to_vec()).into()))
}
pub fn fingerprint(encoded: &str) -> Result<String> {
    let (certs, _) = material(encoded)?;
    Ok(crypto::hex(&crypto::hash(
        certs
            .first()
            .ok_or_else(|| anyhow!("TLS certificate missing"))?
            .as_ref(),
    )))
}
pub fn server(encoded: &str) -> Result<Arc<rustls::ServerConfig>> {
    let (certs, key) = material(encoded)?;
    Ok(Arc::new(
        rustls::ServerConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])?
        .with_no_client_auth()
        .with_single_cert(certs, key)?,
    ))
}

#[derive(Debug)]
pub struct PinVerifier {
    expected: Option<String>,
    observed: Mutex<Option<String>>,
    provider: rustls::crypto::CryptoProvider,
}
impl PinVerifier {
    pub fn new(expected: Option<&str>) -> Result<Arc<Self>> {
        if let Some(pin) = expected {
            if pin.len() != 64 || !pin.bytes().all(|b| b.is_ascii_hexdigit()) {
                bail!("Invalid certificate fingerprint");
            }
        }
        Ok(Arc::new(Self {
            expected: expected.map(str::to_ascii_lowercase),
            observed: Mutex::new(None),
            provider: rustls::crypto::ring::default_provider(),
        }))
    }
    pub fn fingerprint(&self) -> Result<String> {
        self.observed
            .lock()
            .map_err(|_| anyhow!("TLS lock poisoned"))?
            .clone()
            .ok_or_else(|| anyhow!("TLS certificate not observed"))
    }
    pub fn config(self: &Arc<Self>) -> Result<Arc<rustls::ClientConfig>> {
        Ok(Arc::new(
            rustls::ClientConfig::builder_with_provider(Arc::new(
                rustls::crypto::ring::default_provider(),
            ))
            .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])?
            .dangerous()
            .with_custom_certificate_verifier(self.clone())
            .with_no_client_auth(),
        ))
    }
}
impl ServerCertVerifier for PinVerifier {
    fn verify_server_cert(
        &self,
        end: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        let (_, cert) = x509_parser::parse_x509_certificate(end.as_ref())
            .map_err(|_| rustls::Error::General("Invalid certificate".into()))?;
        let t = now.as_secs() as i64;
        if t < cert.validity().not_before.timestamp() || t > cert.validity().not_after.timestamp() {
            return Err(rustls::Error::General(
                "Certificate expired or not yet valid".into(),
            ));
        }
        let actual = crypto::hex(&crypto::hash(end.as_ref()));
        let mut observed = self
            .observed
            .lock()
            .map_err(|_| rustls::Error::General("TLS state unavailable".into()))?;
        if self.expected.as_ref().is_some_and(|p| p != &actual)
            || observed.as_ref().is_some_and(|p| p != &actual)
        {
            return Err(rustls::Error::General("Server fingerprint changed".into()));
        }
        *observed = Some(actual);
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self,
        m: &[u8],
        cert: &CertificateDer<'_>,
        d: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            m,
            cert,
            d,
            &self.provider.signature_verification_algorithms,
        )
    }
    fn verify_tls13_signature(
        &self,
        m: &[u8],
        cert: &CertificateDer<'_>,
        d: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            m,
            cert,
            d,
            &self.provider.signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}
