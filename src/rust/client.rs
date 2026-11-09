use crate::{
    address, auth, crypto, http, images, models::*, server::text, tls::PinVerifier, vault::Vault,
};
use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Value};
use std::collections::BTreeMap as Map;
use zeroize::Zeroizing;
#[derive(Debug, thiserror::Error)]
#[error("{message}")]
pub struct ApiException {
    pub status: u16,
    pub message: String,
    pub retry_after: u32,
}
pub struct ClientCore {
    pub state: ClientState,
    vault: Vault,
    tokens: Map<String, Zeroizing<String>>,
    pub typing: Map<String, Map<String, String>>,
    pub incoming_calls: Map<String, Vec<CallInvite>>,
}
impl ClientCore {
    pub fn open(vault: Vault) -> Result<Self> {
        let mut state: ClientState = if let Some(stored) = vault.data.get("client") {
            serde_json::from_value(stored.clone())?
        } else {
            let (public_key, private_key) = crypto::identity()?;
            ClientState {
                public_key,
                private_key,
                ..ClientState::default()
            }
        };
        for s in &mut state.servers {
            let stored_legacy = vault.data["client"]["servers"]
                .as_array()
                .and_then(|ss| ss.iter().find(|v| text(v, "id") == s.id))
                .is_some_and(|v| v.get("joinProtocol").and_then(Value::as_u64).unwrap_or(0) < 2);
            if s.join_protocol < 2 || stored_legacy {
                s.address = address::migrate_legacy(&s.address)?;
                s.join_protocol = 2;
            }
        }
        let mut c = Self {
            state,
            vault,
            tokens: Map::new(),
            typing: Map::new(),
            incoming_calls: Map::new(),
        };
        c.save()?;
        Ok(c)
    }
    pub fn save(&mut self) -> Result<()> {
        self.vault.data["client"] = serde_json::to_value(&self.state)?;
        self.vault.save()
    }
    pub fn account(&self, s: &SavedServer) -> String {
        crypto::account(&s.id, &self.state.public_key)
    }
    pub fn connected(&self, s: &SavedServer) -> bool {
        self.tokens.contains_key(&s.id)
    }
    pub fn disconnect(&mut self, s: &SavedServer) {
        self.tokens.remove(&s.id);
        self.incoming_calls.remove(&s.id);
        self.typing.remove(&s.id);
    }
    pub fn connection_settings(
        &mut self,
        s: &mut SavedServer,
        tor_only: bool,
        socks_port: u16,
    ) -> Result<()> {
        anyhow::ensure!(
            socks_port != 0,
            "Tor SOCKS port must be between 1 and 65535"
        );
        self.disconnect(s);
        s.tor_only = tor_only;
        s.tor_socks_port = socks_port;
        self.store(s)?;
        Ok(())
    }
    fn exchange(
        &mut self,
        s: &SavedServer,
        path: &str,
        input: &Value,
        auth: bool,
        verifier: &std::sync::Arc<PinVerifier>,
    ) -> Result<Value> {
        let token = if auth {
            Some(
                self.tokens
                    .get(&s.id)
                    .context("Server is disconnected")?
                    .as_str(),
            )
        } else {
            None
        };
        let r = http::post(
            &s.address,
            path,
            if s.tor_only {
                Some(s.tor_socks_port)
            } else {
                None
            },
            verifier,
            &serde_json::to_vec(input)?,
            token,
        )?;
        let value: Value =
            serde_json::from_slice(&r.body).context("Invalid server JSON response")?;
        if r.status != 200 {
            if auth && r.status == 401 {
                self.tokens.remove(&s.id);
            }
            return Err(ApiException {
                status: r.status,
                message: text(&value, "error").into(),
                retry_after: value["retryAfterSeconds"].as_u64().unwrap_or(0) as u32,
            }
            .into());
        }
        anyhow::ensure!(value.is_object(), "Server returned a non-object response");
        Ok(value)
    }
    pub fn request(
        &mut self,
        s: &SavedServer,
        path: &str,
        input: &Value,
        auth: bool,
    ) -> Result<Value> {
        let pin = s
            .fingerprint
            .as_deref()
            .context("Server certificate has not been authenticated")?;
        self.exchange(s, path, input, auth, &PinVerifier::new(Some(pin))?)
    }
    pub fn connect(
        &mut self,
        s: &mut SavedServer,
        password: Option<Zeroizing<String>>,
    ) -> Result<()> {
        let mut candidate = s.clone();
        candidate.address = address::normalize(&s.address)?;
        let bootstrap = PinVerifier::new(s.fingerprint.as_deref())?;
        let challenge = self.exchange(&candidate, "challenge", &json!({}), false, &bootstrap)?;
        anyhow::ensure!(
            text(&challenge, "auth") == auth::PROTOCOL
                && text(&challenge, "algorithm") == "SHA-256",
            "Unsupported authentication protocol"
        );
        let server = text(&challenge, "server");
        let fingerprint = bootstrap.fingerprint()?;
        let nonce = text(&challenge, "nonce");
        let salt = crypto::unb64(text(&challenge, "salt"))?;
        let key = if let Some(password) = password {
            Zeroizing::new(crypto::password_key(&password, &salt).to_vec())
        } else {
            Zeroizing::new(crypto::unb64(
                s.password_key
                    .as_deref()
                    .context("Join using the server password first")?,
            )?)
        };
        let srp = auth::Client::new();
        let a = srp.public.to_str_radix(16);
        let evidence = srp.evidence(
            &salt,
            &auth::identity(server, &fingerprint),
            &key,
            text(&challenge, "B"),
        )?;
        let response = self.exchange(
            &candidate,
            "auth",
            &json!({"nonce":nonce,"A":a,"M1":evidence.m1}),
            false,
            &bootstrap,
        )?;
        let session = evidence.verify(text(&response, "M2"))?;
        anyhow::ensure!(
            text(&challenge, "keyDistribution") == "server-v1",
            "Server does not support automatic channel keys"
        );
        anyhow::ensure!(s.id.is_empty() || s.id == server, "Server identity changed");
        candidate.id = server.into();
        candidate.fingerprint = Some(fingerprint.clone());
        candidate.name = text(&challenge, "name").into();
        candidate.join_protocol = 2;
        let bits = challenge["bits"]
            .as_u64()
            .filter(|b| *b <= 255)
            .context("Invalid registration work factor")? as u8;
        let solution = crypto::solve(&format!("{nonce}|{}", self.account(&candidate)), bits)?;
        let transcript = auth::transcript(
            server,
            &fingerprint,
            nonce,
            &self.state.public_key,
            &solution,
        );
        let login = json!({"nonce":nonce,"solution":solution,"publicKey":self.state.public_key,"proof":crypto::b64(&crypto::hmac(session.as_ref(),transcript.as_bytes())),"signature":crypto::sign(&self.state.private_key,transcript.as_bytes())?});
        let response = self.request(&candidate, "login", &login, false)?;
        anyhow::ensure!(
            text(&response, "hash") == self.account(&candidate)
                && text(&response, "server") == candidate.id
                && !text(&response, "token").is_empty(),
            "Invalid login identity"
        );
        self.tokens.insert(
            candidate.id.clone(),
            Zeroizing::new(text(&response, "token").into()),
        );
        candidate.password_key = Some(crypto::b64(&key));
        *s = candidate;
        self.update_profile(s)?;
        self.poll(s)?;
        self.store(s)
    }
    pub fn store(&mut self, s: &SavedServer) -> Result<()> {
        if let Some(stored) = self
            .state
            .servers
            .iter_mut()
            .find(|old| old.id == s.id && !s.id.is_empty())
        {
            *stored = s.clone();
        } else {
            self.state.servers.push(s.clone());
        }
        self.save()
    }
    pub fn update_profile(&mut self, s: &SavedServer) -> Result<()> {
        let profile = json!({"name":self.state.name,"bio":self.state.bio,"avatar":self.state.avatar,"pronouns":self.state.pronouns});
        self.request(s, "profile", &profile, true)?;
        Ok(())
    }
    pub fn poll(&mut self, s: &mut SavedServer) -> Result<bool> {
        let r = self.request(s, "poll", &json!({"after":s.last_sequence}), true)?;
        let mut next = s.clone();
        let accounts: Map<String, Account> = serde_json::from_value(r["accounts"].clone())?;
        for (hash, a) in &accounts {
            anyhow::ensure!(
                *hash == a.hash && crypto::account(&s.id, &a.public_key) == *hash,
                "Account identity mismatch"
            );
        }
        let messages: Vec<Envelope> = serde_json::from_value(r["messages"].clone())?;
        for e in messages {
            let a = accounts.get(&e.author).context("Message account missing")?;
            anyhow::ensure!(
                e.server == s.id && crypto::verify(&a.public_key, &e.signed_bytes(), &e.signature)?,
                "Message signature failed verification"
            );
            if !next
                .messages
                .iter()
                .any(|m| m.id == e.id && m.author == e.author)
            {
                next.messages.push(e);
            }
        }
        next.accounts = accounts;
        next.name = text(&r, "name").into();
        next.description = text(&r, "description").into();
        next.image = text(&r, "image").into();
        next.admin = r["admin"].as_bool().unwrap_or(false);
        next.role_manager = r["roleManager"].as_bool().unwrap_or(false);
        next.channels = serde_json::from_value(r["channels"].clone())?;
        next.admin_channels = serde_json::from_value(r["adminChannels"].clone())?;
        next.roles = serde_json::from_value(r["roles"].clone())?;
        next.everyone_permissions = serde_json::from_value(r["everyonePermissions"].clone())?;
        next.friendships = serde_json::from_value(r["friendships"].clone())?;
        next.friend_requests = serde_json::from_value(r["friendRequests"].clone())?;
        next.last_sequence = r["cursor"].as_u64().context("Invalid polling cursor")?;
        anyhow::ensure!(
            next.last_sequence >= s.last_sequence,
            "Server cursor moved backwards"
        );
        apply_channel_keys(&mut next, &r["channelKeys"])?;
        let typing: Map<String, String> = serde_json::from_value(r["typing"].clone())?;
        let calls: Vec<CallInvite> = serde_json::from_value(r["calls"].clone())?;
        let changed = next != *s
            || self.typing.get(&s.id) != Some(&typing)
            || self.incoming_calls.get(&s.id) != Some(&calls);
        self.typing.insert(s.id.clone(), typing);
        self.incoming_calls.insert(s.id.clone(), calls);
        if next != *s {
            *s = next;
            self.store(s)?;
        }
        Ok(changed)
    }
    pub fn refresh_channel_keys(&mut self, s: &mut SavedServer) -> Result<()> {
        let r = self.request(s, "keys", &json!({}), true)?;
        self.accept_channel_keys(s, &r["channelKeys"])
    }
    pub fn accept_channel_keys(&mut self, s: &mut SavedServer, supplied: &Value) -> Result<()> {
        let mut next = s.clone();
        apply_channel_keys(&mut next, supplied)?;
        if next != *s {
            *s = next;
            self.store(s)?;
        }
        Ok(())
    }
    pub fn envelope(
        &self,
        s: &SavedServer,
        channel: &str,
        kind: &str,
        bytes: &[u8],
    ) -> Result<Envelope> {
        let key = s.channel_keys.get(channel).context("Channel key needed")?;
        let mut e = Envelope {
            id: id(),
            server: s.id.clone(),
            channel: channel.into(),
            author: self.account(s),
            kind: kind.into(),
            time: crate::now(),
            key_revision: *s.key_revisions.get(channel).unwrap_or(&0),
            ..Envelope::default()
        };
        e.cipher = crypto::b64(&crypto::seal(
            &crypto::unb64(key)?,
            &crypto::compress(bytes)?,
            &e.aad(),
        )?);
        e.signature = crypto::sign(&self.state.private_key, &e.signed_bytes())?;
        Ok(e)
    }
    pub fn send(
        &mut self,
        s: &mut SavedServer,
        c: &Channel,
        kind: &str,
        bytes: &[u8],
    ) -> Result<()> {
        anyhow::ensure!(
            c.kind == "text" && ["text", "image", "images"].contains(&kind),
            "Text channel required"
        );
        match kind {
            "text" => {
                anyhow::ensure!(
                    !bytes.is_empty() && bytes.len() <= 16000,
                    "Text limit is 16,000 UTF-8 bytes"
                );
                std::str::from_utf8(bytes)?;
            }
            "image" => anyhow::ensure!(
                bytes.len() <= 100000 && images::is_webp(bytes),
                "Images must be WebP, at most 100,000 bytes"
            ),
            "images" => {
                anyhow::ensure!(bytes.len() <= 425000, "Image collection too large");
                let collection: Vec<String> = serde_json::from_slice(bytes)?;
                let decoded = collection
                    .iter()
                    .map(|s| crypto::unb64(s))
                    .collect::<Result<Vec<_>>>()?;
                images::collection(&decoded)?;
            }
            _ => unreachable!(),
        }
        for attempt in 0..2 {
            let e = self.envelope(s, &c.id, kind, bytes)?;
            match self.request(s, "send", &serde_json::to_value(e)?, true) {
                Ok(_) => {
                    self.poll(s)?;
                    return Ok(());
                }
                Err(err)
                    if attempt == 0
                        && err
                            .downcast_ref::<ApiException>()
                            .is_some_and(|e| e.status == 409) =>
                {
                    self.poll(s)?;
                }
                Err(err) => return Err(err),
            }
        }
        bail!("Message could not be sent")
    }
    pub fn decrypt(&self, s: &SavedServer, e: &Envelope) -> Result<Vec<u8>> {
        let a = s
            .accounts
            .get(&e.author)
            .context("Message author missing")?;
        anyhow::ensure!(
            s.id == e.server
                && crypto::account(&s.id, &a.public_key) == e.author
                && crypto::verify(&a.public_key, &e.signed_bytes(), &e.signature)?,
            "Signature verification failed"
        );
        let key = s
            .channel_keys
            .get(&e.channel)
            .context("Channel key needed")?;
        let sealed = crypto::unb64(&e.cipher)?;
        let limit = match e.kind.as_str() {
            "images" => 425000,
            "image" => 100000,
            "voice" => 32000,
            k if k.starts_with("video/") => 125000,
            _ => 16000,
        };
        for candidate in std::iter::once(key).chain(
            s.previous_channel_keys
                .get(&e.channel)
                .into_iter()
                .flatten(),
        ) {
            if let Ok(plain) = crypto::open(&crypto::unb64(candidate)?, &sealed, &e.aad()) {
                return crypto::decompress(&plain, limit);
            }
        }
        Err(anyhow!("This message needs a different channel key"))
    }
}
pub fn apply_channel_keys(s: &mut SavedServer, supplied: &Value) -> Result<()> {
    let supplied = supplied
        .as_object()
        .context("Server channel keys missing")?;
    let mut next = s.clone();
    for (id, k) in supplied {
        let key = validated_key(text(k, "current"))?;
        let revision = k["revision"].as_u64().context("Invalid key revision")?;
        let known = s.key_revisions.get(id).copied().unwrap_or(0);
        anyhow::ensure!(
            revision >= 1
                && revision >= known
                && (revision != known || s.channel_keys.get(id) == Some(&key)),
            "Server channel encryption revision is invalid"
        );
        let mut previous = s.previous_channel_keys.get(id).cloned().unwrap_or_default();
        if let Some(old) = s.channel_keys.get(id) {
            if old != &key && !previous.contains(old) {
                previous.push(old.clone());
            }
        }
        let supplied: Vec<String> =
            serde_json::from_value(k.get("previous").cloned().unwrap_or(json!([])))?;
        for p in supplied {
            let p = validated_key(&p)?;
            if p != key && !previous.contains(&p) {
                previous.push(p);
            }
        }
        previous.retain(|p| p != &key);
        next.channel_keys.insert(id.clone(), key);
        next.previous_channel_keys.insert(id.clone(), previous);
        next.key_revisions.insert(id.clone(), revision);
    }
    *s = next;
    Ok(())
}
fn validated_key(key: &str) -> Result<String> {
    let b = crypto::unb64(key)?;
    anyhow::ensure!(b.len() == 32, "Invalid server channel encryption key");
    Ok(crypto::b64(&b))
}
