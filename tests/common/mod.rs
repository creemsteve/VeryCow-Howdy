#![allow(dead_code)]
use serde_json::{json, Value};
use vch_core::{
    auth, crypto,
    models::*,
    server::{ApiError, ChatServer},
    vault::Vault,
};
use zeroize::Zeroizing;
pub struct Fixture {
    pub server: ChatServer,
    pub now: u64,
    _dir: tempfile::TempDir,
}
#[derive(Clone)]
pub struct Member {
    pub ip: String,
    pub token: String,
    pub hash: String,
    pub public: String,
    pub private: String,
}
impl Fixture {
    pub fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let mut server = ChatServer::open(
            Vault::open(
                dir.path().join("data.vault"),
                Zeroizing::new("test vault password".into()),
            )
            .unwrap(),
        )
        .unwrap();
        server
            .set_password(Zeroizing::new("test join password".into()))
            .unwrap();
        server.state.hash_bits = 8;
        Self {
            server,
            now: vch_core::now(),
            _dir: dir,
        }
    }
    pub fn join(&mut self, ip: &str) -> Member {
        let ticket = self.server.admit(ip, 0).unwrap();
        let challenge = self
            .server
            .route(ip, "challenge", None, &json!({}), Some(&ticket), self.now)
            .unwrap();
        let salt = crypto::unb64(challenge["salt"].as_str().unwrap()).unwrap();
        let password = crypto::password_key("test join password", &salt);
        let srp = auth::Client::new();
        let a = srp.public.to_str_radix(16);
        let evidence = srp
            .evidence(
                &salt,
                &auth::identity(&self.server.state.id, self.server.fingerprint()),
                password.as_ref(),
                challenge["B"].as_str().unwrap(),
            )
            .unwrap();
        let nonce = challenge["nonce"].as_str().unwrap();
        let response = self
            .server
            .route(
                ip,
                "auth",
                None,
                &json!({"nonce":nonce,"A":a,"M1":evidence.m1}),
                None,
                self.now,
            )
            .unwrap();
        let key = evidence.verify(response["M2"].as_str().unwrap()).unwrap();
        let (public, private) = crypto::identity().unwrap();
        let hash = crypto::account(&self.server.state.id, &public);
        let solution = crypto::solve(&format!("{nonce}|{hash}"), 8).unwrap();
        let transcript = auth::transcript(
            &self.server.state.id,
            self.server.fingerprint(),
            nonce,
            &public,
            &solution,
        );
        let response=self.server.route(ip,"login",None,&json!({"nonce":nonce,"publicKey":public,"solution":solution,"proof":crypto::b64(&crypto::hmac(key.as_ref(),transcript.as_bytes())),"signature":crypto::sign(&private,transcript.as_bytes()).unwrap()}),None,self.now).unwrap();
        Member {
            ip: ip.into(),
            token: response["token"].as_str().unwrap().into(),
            hash,
            public,
            private,
        }
    }
    pub fn request(&mut self, m: &Member, path: &str, input: Value) -> Result<Value, ApiError> {
        self.server
            .route(&m.ip, path, Some(&m.token), &input, None, self.now)
    }
    pub fn envelope(&self, m: &Member, channel: &str, kind: &str, plain: &[u8]) -> Envelope {
        let keys = &self.server.state.channel_keys[channel];
        let mut e = Envelope {
            id: id(),
            server: self.server.state.id.clone(),
            channel: channel.into(),
            author: m.hash.clone(),
            kind: kind.into(),
            time: self.now,
            key_revision: keys.revision,
            ..Envelope::default()
        };
        e.cipher = crypto::b64(
            &crypto::seal(
                &crypto::unb64(&keys.current).unwrap(),
                &crypto::compress(plain).unwrap(),
                &e.aad(),
            )
            .unwrap(),
        );
        e.signature = crypto::sign(&m.private, &e.signed_bytes()).unwrap();
        e
    }
}
pub fn status(result: Result<Value, ApiError>, expected: u16) {
    assert_eq!(result.unwrap_err().status, expected);
}
