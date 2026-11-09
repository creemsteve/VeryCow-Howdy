//! Bouncy Castle compatible SRP-6a, including its padded evidence hashes and signed Java BigInteger session encoding.
use crate::crypto;
use anyhow::{bail, Result};
use num_bigint::{BigUint, RandBigInt};
use num_traits::{One, Zero};
pub const PROTOCOL: &str = "VCH-SRP6a-SHA256-3072-v2";
const GROUP:&str="FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";
fn modulus() -> BigUint {
    BigUint::parse_bytes(GROUP.as_bytes(), 16).unwrap()
}
fn generator() -> BigUint {
    BigUint::from(5u8)
}
fn padded(n: &BigUint) -> Vec<u8> {
    let b = n.to_bytes_be();
    let mut p = vec![0; 384usize.saturating_sub(b.len())];
    p.extend(b);
    p
}
fn digest(numbers: &[&BigUint]) -> BigUint {
    BigUint::from_bytes_be(&crypto::hash(
        &numbers.iter().flat_map(|n| padded(n)).collect::<Vec<_>>(),
    ))
}
fn x(salt: &[u8], identity: &[u8], password: &[u8]) -> BigUint {
    let inner = crypto::hash(&[identity, b":", password].concat());
    BigUint::from_bytes_be(&crypto::hash(&[salt, &inner].concat()))
}
fn private() -> BigUint {
    rand::rngs::OsRng.gen_biguint_range(&(BigUint::one() << 255), &modulus())
}
pub fn identity(server: &str, fingerprint: &str) -> Vec<u8> {
    format!("{PROTOCOL}|{server}|{fingerprint}").into_bytes()
}
pub fn number(hex: &str) -> Result<BigUint> {
    if hex.is_empty() || hex.len() > 768 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        bail!("Invalid password handshake");
    }
    let n = BigUint::parse_bytes(hex.as_bytes(), 16).unwrap();
    if n.is_zero() || n >= modulus() {
        bail!("Invalid password handshake");
    }
    Ok(n)
}
pub fn transcript(server: &str, fp: &str, nonce: &str, public: &str, solution: &str) -> String {
    format!("VCH/login/2|{server}|{fp}|{nonce}|{public}|{solution}")
}
fn session(s: &BigUint) -> [u8; 32] {
    let k = digest(&[s]);
    let mut b = k.to_bytes_be();
    if b.first().is_some_and(|v| v & 128 != 0) {
        b.insert(0, 0);
    }
    crypto::hash(&b)
}
pub struct Server {
    b: BigUint,
    v: BigUint,
    pub public: BigUint,
}
impl Server {
    pub fn new(salt: &[u8], identity: &[u8], password: &[u8]) -> Self {
        let n = modulus();
        let g = generator();
        let v = g.modpow(&x(salt, identity, password), &n);
        let b = private();
        let public = (digest(&[&n, &g]) * &v + g.modpow(&b, &n)) % &n;
        Self { b, v, public }
    }
    pub fn authenticate(self, a: &str, m1: &str) -> Result<(String, zeroize::Zeroizing<[u8; 32]>)> {
        let a = number(a)?;
        let m1 = number(m1)?;
        let n = modulus();
        let u = digest(&[&a, &self.public]);
        let s = (self.v.modpow(&u, &n) * &a % &n).modpow(&self.b, &n);
        let expected = digest(&[&a, &self.public, &s]);
        if padded(&m1) != padded(&expected) {
            bail!("Authentication failed");
        }
        Ok((
            digest(&[&a, &m1, &s]).to_str_radix(16),
            zeroize::Zeroizing::new(session(&s)),
        ))
    }
}
pub struct Client {
    a: BigUint,
    pub public: BigUint,
}
pub struct Evidence {
    pub m1: String,
    expected: BigUint,
    key: zeroize::Zeroizing<[u8; 32]>,
}
impl Client {
    pub fn new() -> Self {
        let a = private();
        let public = generator().modpow(&a, &modulus());
        Self { a, public }
    }
    pub fn evidence(
        self,
        salt: &[u8],
        identity: &[u8],
        password: &[u8],
        b: &str,
    ) -> Result<Evidence> {
        let b = number(b)?;
        let n = modulus();
        let g = generator();
        let x = x(salt, identity, password);
        let u = digest(&[&self.public, &b]);
        let k = digest(&[&n, &g]);
        let base = (&b + &n - ((k * g.modpow(&x, &n)) % &n)) % &n;
        let s = base.modpow(&(self.a + u * x), &n);
        let m1 = digest(&[&self.public, &b, &s]);
        let expected = digest(&[&self.public, &m1, &s]);
        Ok(Evidence {
            m1: m1.to_str_radix(16),
            expected,
            key: zeroize::Zeroizing::new(session(&s)),
        })
    }
}
impl Default for Client {
    fn default() -> Self {
        Self::new()
    }
}
impl Evidence {
    pub fn verify(self, m2: &str) -> Result<zeroize::Zeroizing<[u8; 32]>> {
        if number(m2)? != self.expected {
            bail!("Server password proof failed; certificate was not trusted");
        }
        Ok(self.key)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn java_exchange() {
        let f: serde_json::Value =
            serde_json::from_str(include_str!("../../tests/fixtures/java.json")).unwrap();
        let salt = crypto::unb64(f["salt"].as_str().unwrap()).unwrap();
        let key = crypto::unb64(f["passwordKey"].as_str().unwrap()).unwrap();
        let identity = identity("compatibility-server", &"a".repeat(64));
        let a = number(f["a"].as_str().unwrap()).unwrap();
        let b = number(f["b"].as_str().unwrap()).unwrap();
        let n = modulus();
        assert_eq!(n.bits(), 3072);
        let public = generator().modpow(&a, &n);
        assert_eq!(public.to_str_radix(16), f["A"]);
        let client = Client { a, public };
        let evidence = client
            .evidence(&salt, &identity, &key, f["B"].as_str().unwrap())
            .unwrap();
        assert_eq!(evidence.m1, f["M1"]);
        let session = evidence.verify(f["M2"].as_str().unwrap()).unwrap();
        assert_eq!(crypto::b64(session.as_ref()), f["session"]);
        let v = generator().modpow(&x(&salt, &identity, &key), &n);
        let server = Server {
            b,
            v,
            public: number(f["B"].as_str().unwrap()).unwrap(),
        };
        let (m2, k) = server
            .authenticate(f["A"].as_str().unwrap(), f["M1"].as_str().unwrap())
            .unwrap();
        assert_eq!(m2, f["M2"]);
        assert_eq!(crypto::b64(k.as_ref()), f["session"]);
    }
    #[test]
    fn mutual_auth_and_attacks() {
        for wrong in [0, 1, 2] {
            let salt = crypto::random(16);
            let password = crypto::random(32);
            let identity = identity("s", &"b".repeat(64));
            let server = Server::new(&salt, &identity, &password);
            let client = Client::new();
            let a = client.public.to_str_radix(16);
            let e = client
                .evidence(
                    &salt,
                    if wrong == 2 {
                        b"substituted certificate"
                    } else {
                        &identity
                    },
                    if wrong == 1 {
                        b"wrong password"
                    } else {
                        &password
                    },
                    &server.public.to_str_radix(16),
                )
                .unwrap();
            let result = server.authenticate(&a, &e.m1);
            if wrong == 0 {
                let (m2, key) = result.unwrap();
                assert_eq!(e.verify(&m2).unwrap().as_ref(), key.as_ref());
            } else {
                assert!(result.is_err());
            }
        }
        for value in ["0", "-1", "xyz", GROUP] {
            assert!(number(value).is_err());
        }
        let s = Server::new(b"salt", b"identity", b"key");
        let c = Client::default();
        let e = c
            .evidence(b"salt", b"identity", b"key", &s.public.to_str_radix(16))
            .unwrap();
        assert!(e.verify("1").is_err());
        assert!(Client::new().evidence(b"salt", b"i", b"p", "0").is_err());
        assert_eq!(transcript("s", "f", "n", "p", "w"), "VCH/login/2|s|f|n|p|w");
    }
}
