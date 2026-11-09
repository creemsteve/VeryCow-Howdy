use aes_gcm::{
    aead::{Aead, KeyInit as AesKeyInit, Payload},
    Aes256Gcm, Nonce,
};
use anyhow::{anyhow, bail, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use hmac::{Hmac, Mac};
use ml_dsa::pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePrivateKey, EncodePublicKey};
use ml_dsa::{Keypair, MlDsa65, Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand::{rngs::OsRng, RngCore};
use sha2::{Digest, Sha256};
use std::io::{Read, Write};

pub fn random(n: usize) -> Vec<u8> {
    let mut bytes = vec![0; n];
    OsRng.fill_bytes(&mut bytes);
    bytes
}
pub fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}
pub fn unb64(text: &str) -> Result<Vec<u8>> {
    Ok(STANDARD.decode(text)?)
}
pub fn hash(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
pub fn derive(password: &str, salt: &[u8]) -> Result<zeroize::Zeroizing<[u8; 32]>> {
    let params = argon2::Params::new(65536, 3, 1, Some(32)).map_err(|e| anyhow!(e.to_string()))?;
    let mut key = zeroize::Zeroizing::new([0; 32]);
    argon2::Argon2::new(argon2::Algorithm::Argon2id, argon2::Version::V0x13, params)
        .hash_password_into(password.as_bytes(), salt, key.as_mut())
        .map_err(|e| anyhow!(e.to_string()))?;
    Ok(key)
}
pub fn password_key(password: &str, salt: &[u8]) -> zeroize::Zeroizing<[u8; 32]> {
    let mut key = zeroize::Zeroizing::new([0; 32]);
    pbkdf2::pbkdf2_hmac::<Sha256>(password.as_bytes(), salt, 310000, key.as_mut());
    key
}
pub fn hmac(key: &[u8], bytes: &[u8]) -> Vec<u8> {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC accepts all key sizes");
    m.update(bytes);
    m.finalize().into_bytes().to_vec()
}
pub fn verify_hmac(key: &[u8], bytes: &[u8], expected: &[u8]) -> bool {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC accepts all key sizes");
    m.update(bytes);
    m.verify_slice(expected).is_ok()
}
pub fn seal(key: &[u8], plain: &[u8], aad: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| anyhow!("Invalid AES key"))?;
    let nonce = random(12);
    let encrypted = cipher
        .encrypt(Nonce::from_slice(&nonce), Payload { msg: plain, aad })
        .map_err(|_| anyhow!("Encryption failed"))?;
    Ok([nonce, encrypted].concat())
}
pub fn open(key: &[u8], sealed: &[u8], aad: &[u8]) -> Result<Vec<u8>> {
    if sealed.len() < 28 {
        bail!("Invalid encrypted data");
    }
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| anyhow!("Invalid AES key"))?;
    cipher
        .decrypt(
            Nonce::from_slice(&sealed[..12]),
            Payload {
                msg: &sealed[12..],
                aad,
            },
        )
        .map_err(|_| anyhow!("Authentication failed"))
}
pub fn compress(plain: &[u8]) -> Result<Vec<u8>> {
    let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    z.write_all(plain)?;
    Ok(z.finish()?)
}
pub fn decompress(compressed: &[u8], max: usize) -> Result<Vec<u8>> {
    let mut z = flate2::read::ZlibDecoder::new(compressed).take(max as u64 + 1);
    let mut b = Vec::new();
    z.read_to_end(&mut b)?;
    if b.len() > max {
        bail!("Decompressed data exceeds limit");
    }
    Ok(b)
}
pub fn identity() -> Result<(String, String)> {
    let seed: ml_dsa::Seed = random(32).as_slice().try_into()?;
    let sk = SigningKey::<MlDsa65>::from_seed(&seed);
    let public = b64(sk.verifying_key().to_public_key_der()?.as_bytes());
    let private = b64(sk.to_pkcs8_der()?.as_bytes());
    Ok((public, private))
}
pub fn sign(private: &str, bytes: &[u8]) -> Result<String> {
    let raw = zeroize::Zeroizing::new(unb64(private)?);
    let sk = match SigningKey::<MlDsa65>::from_pkcs8_der(&raw) {
        Ok(sk) => sk,
        Err(original) => {
            // Bouncy Castle 1.83 exports the seed and expanded key as a SEQUENCE.
            // Recover the seed without changing the public key or account hash.
            use ml_dsa::pkcs8::{
                der::{asn1::OctetStringRef, Decode},
                PrivateKeyInfoRef,
            };
            let info = PrivateKeyInfoRef::from_der(&raw)?;
            anyhow::ensure!(
                info.algorithm.oid.to_string() == "2.16.840.1.101.3.4.3.18",
                "Invalid ML-DSA algorithm"
            );
            let sequence = Vec::<&OctetStringRef>::from_der(info.private_key.as_bytes())
                .map_err(|_| original)?;
            let seed: ml_dsa::Seed = sequence
                .first()
                .ok_or_else(|| anyhow!("Missing identity seed"))?
                .as_bytes()
                .try_into()?;
            SigningKey::<MlDsa65>::from_seed(&seed)
        }
    };
    let sig: Signature<MlDsa65> = sk.try_sign(bytes)?;
    Ok(b64(sig.encode().as_slice()))
}
pub fn verify(public: &str, bytes: &[u8], sig: &str) -> Result<bool> {
    let vk = VerifyingKey::<MlDsa65>::from_public_key_der(&unb64(public)?)?;
    let raw = unb64(sig)?;
    let signature = match Signature::<MlDsa65>::try_from(raw.as_slice()) {
        Ok(s) => s,
        Err(_) => return Ok(false),
    };
    Ok(vk.verify(bytes, &signature).is_ok())
}
pub fn account(server: &str, public: &str) -> String {
    hex(&hash(format!("VCH/account/1|{server}|{public}").as_bytes()))
}
pub fn proof(prefix: &str, answer: &str, bits: u8) -> bool {
    if answer.len() > 24 || !(8..=24).contains(&bits) {
        return false;
    }
    let h = hash(format!("{prefix}|{answer}").as_bytes());
    (0..bits).all(|i| h[(i / 8) as usize] & (1 << (7 - i % 8)) == 0)
}
pub fn solve(prefix: &str, bits: u8) -> Result<String> {
    if !(8..=22).contains(&bits) {
        bail!("Unsupported registration work factor");
    }
    for n in 0..u64::MAX {
        let answer = n.to_string();
        if proof(prefix, &answer, bits) {
            return Ok(answer);
        }
    }
    bail!("Work space exhausted")
}
