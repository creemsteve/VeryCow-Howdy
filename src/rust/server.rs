use crate::{
    auth, crypto, http, images, limiter::JoinAttemptLimiter, models::*, tls, vault::Vault,
    video::VideoRelay,
};
use anyhow::{Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap as Map, BTreeSet as Set, VecDeque},
    io::BufReader,
    net::{SocketAddr, TcpListener, TcpStream},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};
use zeroize::Zeroizing;

#[derive(Debug, thiserror::Error)]
#[error("{message}")]
pub struct ApiError {
    pub status: u16,
    pub message: String,
    pub retry_after: u32,
}
impl ApiError {
    pub fn new(status: u16, message: impl Into<String>) -> Self {
        Self {
            status,
            message: message.into(),
            retry_after: 0,
        }
    }
    fn retry(seconds: u32) -> Self {
        Self {
            status: 429,
            message: format!("Wait {seconds} seconds before trying to join again"),
            retry_after: seconds,
        }
    }
}
impl From<anyhow::Error> for ApiError {
    fn from(_: anyhow::Error) -> Self {
        Self::new(400, "Invalid request")
    }
}
impl From<serde_json::Error> for ApiError {
    fn from(_: serde_json::Error) -> Self {
        Self::new(400, "Invalid request")
    }
}
pub type ApiResult<T> = std::result::Result<T, ApiError>;
struct Challenge {
    ip: String,
    expires: u64,
    srp: auth::Server,
}
struct Authenticated {
    ip: String,
    expires: u64,
    key: Zeroizing<[u8; 32]>,
}
#[derive(Clone)]
struct Session {
    account: String,
    ip: String,
    expires: u64,
}
#[derive(Clone)]
struct Presence {
    channel: String,
    expires: u64,
}
#[derive(Clone)]
struct Floor {
    account: String,
    expires: u64,
}
struct VoiceFrame {
    envelope: Envelope,
    cursor: u64,
    expires: u64,
}

pub struct ChatServer {
    vault: Vault,
    pub state: ServerState,
    fingerprint: String,
    challenges: Map<String, Challenge>,
    joins: Map<String, Authenticated>,
    sessions: Map<String, Session>,
    limiter: JoinAttemptLimiter,
    pending: Map<String, String>,
    presence: Map<String, Presence>,
    floors: Map<String, Floor>,
    voice_frames: Map<String, VecDeque<VoiceFrame>>,
    voice_cursor: u64,
    video: VideoRelay,
    typing: Map<String, Presence>,
    calls: Map<String, CallInvite>,
    last_send: Map<String, u64>,
    rates: Map<String, (u64, u32)>,
    clock: Instant,
}
impl ChatServer {
    pub fn open(vault: Vault) -> Result<Self> {
        let mut state: ServerState = if let Some(stored) = vault.data.get("server") {
            let mut s: ServerState = serde_json::from_value(stored.clone())?;
            if stored.get("joinProtocol").is_none() {
                if s.port == 8443 {
                    s.port = 585;
                }
                s.join_protocol = 2;
            }
            s
        } else {
            let mut s = ServerState {
                tls_store: tls::create()?,
                ..ServerState::default()
            };
            s.channels.push(Channel::default());
            s.channels.push(Channel {
                name: "Lounge".into(),
                kind: "voice".into(),
                ..Channel::default()
            });
            s
        };
        if state.hash_bits < 8 || state.hash_bits > 22 {
            state.hash_bits = 16;
        }
        state.banned_ips = state
            .banned_ips
            .iter()
            .map(|ip| canonical_ip(ip).unwrap_or_else(|_| ip.clone()))
            .collect();
        let fingerprint = tls::fingerprint(&state.tls_store)?;
        let mut server = Self {
            vault,
            state,
            fingerprint,
            challenges: Map::new(),
            joins: Map::new(),
            sessions: Map::new(),
            limiter: JoinAttemptLimiter::default(),
            pending: Map::new(),
            presence: Map::new(),
            floors: Map::new(),
            voice_frames: Map::new(),
            voice_cursor: 0,
            video: VideoRelay::default(),
            typing: Map::new(),
            calls: Map::new(),
            last_send: Map::new(),
            rates: Map::new(),
            clock: Instant::now(),
        };
        server.save()?;
        Ok(server)
    }
    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }
    pub fn save(&mut self) -> Result<()> {
        self.ensure_keys()?;
        self.vault.data["server"] = serde_json::to_value(&self.state)?;
        self.vault.save()
    }
    pub fn set_password(&mut self, password: Zeroizing<String>) -> Result<()> {
        anyhow::ensure!(
            password.chars().count() >= 12,
            "Server password requires at least 12 characters"
        );
        let replacing = self.state.password_verifier.is_some();
        let salt = crypto::random(16);
        self.state.password_salt = Some(crypto::b64(&salt));
        self.state.password_verifier =
            Some(crypto::b64(crypto::password_key(&password, &salt).as_ref()));
        if replacing {
            self.rotate_all()?;
        }
        self.reset_sessions();
        self.save()
    }
    pub fn rotate_channel_key(&mut self, id: &str) -> Result<()> {
        self.ensure_keys()?;
        anyhow::ensure!(
            self.state.channels.iter().any(|c| c.id == id),
            "Channel unavailable"
        );
        self.state
            .channel_keys
            .get_mut(id)
            .context("Key unavailable")?
            .rotate();
        self.save()
    }
    fn ensure_keys(&mut self) -> Result<()> {
        for c in &self.state.channels {
            let policy = crypto::hex(&crypto::hash(&serde_json::to_vec(&(
                &c.kind,
                &c.allow,
                &c.role_overrides,
                &self.state.roles,
                &self.state.everyone_permissions,
            ))?));
            let keys = self.state.channel_keys.entry(c.id.clone()).or_default();
            if keys.access_policy.as_ref() != Some(&policy) {
                if keys.access_policy.is_some() {
                    keys.rotate();
                }
                keys.access_policy = Some(policy);
            }
        }
        Ok(())
    }
    fn rotate_all(&mut self) -> Result<()> {
        self.ensure_keys()?;
        for c in &self.state.channels {
            self.state.channel_keys.get_mut(&c.id).unwrap().rotate();
        }
        Ok(())
    }
    fn keys_for(&self, channels: &[Channel]) -> Value {
        let mut keys = serde_json::Map::new();
        for c in channels {
            if let Some(k) = self.state.channel_keys.get(&c.id) {
                keys.insert(
                    c.id.clone(),
                    json!({"current":k.current,"previous":k.previous,"revision":k.revision}),
                );
            }
        }
        Value::Object(keys)
    }
    fn channel(&self, id: &str, account: &str, kind: &str) -> ApiResult<Channel> {
        self.state
            .channels
            .iter()
            .find(|c| c.id == id && c.kind == kind && self.state.permits(c, account, "view"))
            .cloned()
            .ok_or_else(|| ApiError::new(403, "Channel unavailable"))
    }
    pub fn admit(&mut self, ip: &str, monotonic: u64) -> ApiResult<String> {
        if self.state.banned_ips.contains(ip) {
            return Err(ApiError::new(403, "Address banned"));
        }
        let wait = self.limiter.acquire(ip, monotonic);
        if wait > 0 {
            return Err(ApiError::retry(wait));
        }
        self.challenges.retain(|_, c| c.ip != ip);
        self.joins.retain(|_, j| j.ip != ip);
        let ticket = crypto::b64(&crypto::random(16));
        self.pending.insert(ip.into(), ticket.clone());
        Ok(ticket)
    }
    fn admit_now(&mut self, ip: &str) -> ApiResult<String> {
        self.admit(ip, self.clock.elapsed().as_millis() as u64)
    }
    pub fn route(
        &mut self,
        ip: &str,
        path: &str,
        token: Option<&str>,
        input: &Value,
        admission: Option<&str>,
        now: u64,
    ) -> ApiResult<Value> {
        self.cleanup(now);
        if !input.is_object() {
            return Err(ApiError::new(400, "JSON object required"));
        }
        if self.state.banned_ips.contains(ip) {
            return Err(ApiError::new(403, "Address banned"));
        }
        if path != "voice" && path != "video" {
            self.rate(ip, 1200, now)?;
        }
        if path == "challenge" {
            if admission.is_none() || self.pending.get(ip).map(String::as_str) != admission {
                return Err(ApiError::retry(10));
            }
            self.pending.remove(ip);
            if self.challenges.len() > 2048 {
                return Err(ApiError::new(503, "Try again later"));
            }
            let nonce = crypto::b64(&crypto::random(32));
            let salt = self
                .state
                .password_salt
                .as_ref()
                .ok_or_else(|| ApiError::new(503, "Set a join password first"))?;
            let srp = auth::Server::new(
                &crypto::unb64(salt)?,
                &auth::identity(&self.state.id, &self.fingerprint),
                &crypto::unb64(self.state.password_verifier.as_deref().unwrap_or(""))?,
            );
            let b = srp.public.to_str_radix(16);
            self.challenges.insert(
                nonce.clone(),
                Challenge {
                    ip: ip.into(),
                    expires: now + 120000,
                    srp,
                },
            );
            return Ok(
                json!({"server":self.state.id,"name":self.state.name,"nonce":nonce,"salt":salt,"bits":self.state.hash_bits,"algorithm":"SHA-256","auth":auth::PROTOCOL,"B":b,"keyDistribution":"server-v1"}),
            );
        }
        if path == "auth" {
            let nonce = text(input, "nonce");
            if !self.challenges.get(nonce).is_some_and(|c| c.ip == ip) {
                self.admit_now(ip)?;
                return Err(ApiError::new(401, "Password handshake expired"));
            }
            let c = self.challenges.remove(nonce).unwrap();
            self.limiter
                .continued(ip, self.clock.elapsed().as_millis() as u64);
            let (m2, key) = c
                .srp
                .authenticate(text(input, "A"), text(input, "M1"))
                .map_err(|_| ApiError::new(401, "Authentication failed"))?;
            self.joins.insert(
                nonce.into(),
                Authenticated {
                    ip: ip.into(),
                    expires: now + 30000,
                    key,
                },
            );
            return Ok(json!({"M2":m2}));
        }
        if path == "login" {
            let nonce = text(input, "nonce");
            if !self.joins.get(nonce).is_some_and(|j| j.ip == ip) {
                self.admit_now(ip)?;
                return Err(ApiError::new(401, "Password handshake required"));
            }
            let join = self.joins.remove(nonce).unwrap();
            self.limiter
                .continued(ip, self.clock.elapsed().as_millis() as u64);
            let public = text(input, "publicKey");
            if public.len() > 5000 {
                return Err(ApiError::new(400, "Invalid identity"));
            }
            let hash = crypto::account(&self.state.id, public);
            if self.state.banned_hashes.contains(&hash) {
                return Err(ApiError::new(403, "Account banned"));
            }
            let transcript = auth::transcript(
                &self.state.id,
                &self.fingerprint,
                nonce,
                public,
                text(input, "solution"),
            );
            if !crypto::proof(
                &format!("{nonce}|{hash}"),
                text(input, "solution"),
                self.state.hash_bits,
            ) || !crypto::verify_hmac(
                join.key.as_ref(),
                transcript.as_bytes(),
                &crypto::unb64(text(input, "proof"))?,
            ) || !crypto::verify(public, transcript.as_bytes(), text(input, "signature"))?
            {
                return Err(ApiError::new(401, "Authentication failed"));
            }
            if !self.state.accounts.contains_key(&hash) {
                self.state.accounts.insert(
                    hash.clone(),
                    Account {
                        hash: hash.clone(),
                        public_key: public.into(),
                        ..Account::default()
                    },
                );
                self.save()?;
            }
            let token = crypto::b64(&crypto::random(32));
            self.sessions.insert(
                token.clone(),
                Session {
                    account: hash.clone(),
                    ip: ip.into(),
                    expires: now + 8 * 3600000,
                },
            );
            return Ok(json!({"token":token,"hash":hash,"server":self.state.id}));
        }
        let s = match token
            .and_then(|t| self.sessions.get(t))
            .filter(|s| s.ip == ip)
        {
            Some(s) => s.clone(),
            None => {
                if path == "voice" || path == "video" {
                    self.rate(ip, 1200, now)?;
                }
                return Err(ApiError::new(401, "Reconnect to the server"));
            }
        };
        let account = &s.account;
        if self.state.banned_hashes.contains(account) {
            return Err(ApiError::new(403, "Account banned"));
        }
        match path {
            "poll" => {
                let after = input["after"].as_u64().unwrap_or(0);
                let channels: Vec<_> = self
                    .state
                    .channels
                    .iter()
                    .filter(|c| self.state.permits(c, account, "view"))
                    .cloned()
                    .collect();
                let ids: Set<_> = channels.iter().map(|c| c.id.as_str()).collect();
                let messages: Vec<_> = self
                    .state
                    .messages
                    .iter()
                    .filter(|m| m.sequence > after && ids.contains(m.channel.as_str()))
                    .take(10)
                    .collect();
                let cursor = messages
                    .last()
                    .map(|m| m.sequence)
                    .unwrap_or(after.max(self.state.next_sequence - 1));
                let typing: Map<_, _> = self
                    .typing
                    .iter()
                    .filter(|(a, p)| *a != account && ids.contains(p.channel.as_str()))
                    .map(|(a, p)| (a.clone(), p.channel.clone()))
                    .collect();
                Ok(
                    json!({"name":self.state.name,"description":self.state.description,"image":self.state.image,"admin":self.state.admins.contains(account),"roleManager":self.state.can_manage_roles(account),"adminChannels":if self.state.admins.contains(account){self.state.channels.clone()}else{Vec::new()},"roles":if self.state.can_manage_roles(account){self.state.roles.clone()}else{Vec::new()},"everyonePermissions":if self.state.can_manage_roles(account){self.state.everyone_permissions.clone()}else{Set::new()},"calls":self.calls.values().filter(|c|c.to==*account).collect::<Vec<_>>(),"friendships":self.state.friendships.iter().filter(|f|f.includes(account)).collect::<Vec<_>>(),"friendRequests":self.state.friend_requests.get(account).cloned().unwrap_or_default(),"channels":channels,"accounts":self.state.accounts,"messages":messages,"cursor":cursor,"channelKeys":self.keys_for(&channels),"typing":typing}),
                )
            }
            "keys" => {
                let channels: Vec<_> = self
                    .state
                    .channels
                    .iter()
                    .filter(|c| self.state.permits(c, account, "view"))
                    .cloned()
                    .collect();
                Ok(json!({"channelKeys":self.keys_for(&channels)}))
            }
            "profile" => {
                let name = text(input, "name").trim();
                let bio = text(input, "bio");
                let avatar = text(input, "avatar");
                let pronouns = text(input, "pronouns").trim();
                if !valid_name(name, 40)
                    || name.contains(['<', '>'])
                    || bio.chars().count() > 240
                    || pronouns.chars().count() > 40
                    || pronouns.contains(['<', '>'])
                    || pronouns.chars().any(char::is_control)
                {
                    return Err(ApiError::new(400, "Invalid profile"));
                }
                validate_image(avatar, 10000)?;
                let a = self
                    .state
                    .accounts
                    .get_mut(account)
                    .ok_or_else(|| ApiError::new(401, "Unknown account"))?;
                a.name = name.into();
                a.bio = bio.into();
                a.avatar = avatar.into();
                a.pronouns = pronouns.into();
                self.save()?;
                Ok(json!({"ok":true}))
            }
            "typing" => {
                let id = text(input, "channel");
                if id.is_empty() {
                    self.typing.remove(account);
                } else {
                    self.channel(id, account, "text")?;
                    self.typing.insert(
                        account.clone(),
                        Presence {
                            channel: id.into(),
                            expires: now + 5000,
                        },
                    );
                }
                Ok(json!({"ok":true}))
            }
            "send" => self.send(account, input, now),
            "voice" => self.voice(account, input, now),
            "video" => {
                self.rate(&format!("video/{account}"), 2400, now)?;
                let c = self.channel(text(input, "channel"), account, "voice")?;
                if !self.state.permits(&c, account, "connect")
                    || !self
                        .presence
                        .get(account)
                        .is_some_and(|p| p.channel == c.id)
                {
                    return Err(ApiError::new(403, "Join voice before using video"));
                }
                self.video.request(account, &c, &self.state, input, now)
            }
            "friend" => self.friend(account, input),
            "call" => self.call(account, input, now),
            "roles" => self.roles(account, input),
            "admin" => self.admin(account, input),
            _ => Err(ApiError::new(404, "Unknown endpoint")),
        }
    }
    fn send(&mut self, account: &str, input: &Value, now: u64) -> ApiResult<Value> {
        let mut e: Envelope = serde_json::from_value(input.clone())?;
        let c = self.channel(&e.channel, account, "text")?;
        if !self.state.permits(&c, account, "send") {
            return Err(ApiError::new(403, "Sending is not permitted"));
        }
        if e.author != account
            || e.server != self.state.id
            || !valid_id(&e.id)
            || !["text", "image", "images"].contains(&e.kind.as_str())
            || e.cipher.len() > if e.kind == "images" { 600000 } else { 155000 }
            || e.signature.len() > 10000
            || now.abs_diff(e.time) > 300000
            || !crypto::verify(
                &self.state.accounts[account].public_key,
                &e.signed_bytes(),
                &e.signature,
            )?
        {
            return Err(ApiError::new(400, "Invalid message"));
        }
        if self
            .state
            .messages
            .iter()
            .any(|m| m.id == e.id && m.author == e.author)
        {
            return Ok(json!({"ok":true}));
        }
        if e.key_revision != self.state.channel_keys[&c.id].revision {
            return Err(ApiError::new(
                409,
                "Channel encryption changed; refresh and try again",
            ));
        }
        let throttle = format!("{account}/{}", c.id);
        if self
            .last_send
            .get(&throttle)
            .is_some_and(|t| now.saturating_sub(*t) < c.slow_mode_seconds * 1000)
        {
            return Err(ApiError::new(429, "Channel slow mode is active"));
        }
        e.sequence = self.state.next_sequence;
        self.state.next_sequence += 1;
        let sequence = e.sequence;
        self.state.messages.push(e);
        self.last_send.insert(throttle, now);
        if self.state.messages.len() > 10000 {
            self.state.messages.remove(0);
        }
        self.save()?;
        Ok(json!({"ok":true,"sequence":sequence}))
    }
    fn friend(&mut self, account: &str, input: &Value) -> ApiResult<Value> {
        let target = text(input, "account");
        if target == account || !self.state.accounts.contains_key(target) {
            return Err(ApiError::new(400, "Choose another registered account"));
        }
        let existing = self
            .state
            .friendships
            .iter()
            .position(|f| f.includes(account) && f.includes(target));
        match text(input, "action") {
            "request" => {
                if existing.is_some() {
                    return Err(ApiError::new(409, "Already friends"));
                }
                self.state
                    .friend_requests
                    .entry(target.into())
                    .or_default()
                    .insert(account.into());
            }
            "accept" => {
                if existing.is_some()
                    || !self
                        .state
                        .friend_requests
                        .get(account)
                        .is_some_and(|r| r.contains(target))
                {
                    return Err(ApiError::new(409, "Friend request unavailable"));
                }
                self.state
                    .friend_requests
                    .get_mut(account)
                    .unwrap()
                    .remove(target);
                if let Some(r) = self.state.friend_requests.get_mut(target) {
                    r.remove(account);
                }
                let allow = [account.to_string(), target.to_string()].into();
                let text = Channel {
                    name: "Direct message".into(),
                    allow,
                    ..Channel::default()
                };
                let voice = Channel {
                    name: "Friend call".into(),
                    kind: "voice".into(),
                    max_members: 2,
                    allow: text.allow.clone(),
                    ..Channel::default()
                };
                self.state.friendships.push(Friendship {
                    first: account.into(),
                    second: target.into(),
                    text_channel: text.id.clone(),
                    voice_channel: voice.id.clone(),
                });
                self.state.channels.extend([text, voice]);
            }
            "decline" => {
                if let Some(r) = self.state.friend_requests.get_mut(account) {
                    r.remove(target);
                }
            }
            "remove" => {
                let f = self
                    .state
                    .friendships
                    .remove(existing.ok_or_else(|| ApiError::new(404, "Friend unavailable"))?);
                self.calls.retain(|_, c| c.channel != f.voice_channel);
                self.state
                    .channels
                    .retain(|c| c.id != f.text_channel && c.id != f.voice_channel);
                self.state.channel_keys.remove(&f.text_channel);
                self.state.channel_keys.remove(&f.voice_channel);
                self.leave_voice(account);
                self.leave_voice(target);
                self.voice_frames.remove(&f.voice_channel);
            }
            _ => return Err(ApiError::new(400, "Unknown friend action")),
        }
        self.save()?;
        Ok(json!({"ok":true}))
    }
    fn call(&mut self, account: &str, input: &Value, now: u64) -> ApiResult<Value> {
        let action = text(input, "action");
        if action == "ring" {
            let target = text(input, "account");
            let f = self
                .state
                .friendships
                .iter()
                .find(|f| f.includes(account) && f.includes(target) && target != account)
                .ok_or_else(|| ApiError::new(403, "Only friends can be called"))?;
            if self
                .calls
                .values()
                .any(|c| c.to == target || c.from == account)
            {
                return Err(ApiError::new(409, "A call is already ringing"));
            }
            let c = CallInvite {
                id: id(),
                from: account.into(),
                to: target.into(),
                channel: f.voice_channel.clone(),
                expires: now + 30000,
            };
            let r = json!({"id":c.id,"expires":c.expires});
            self.calls.insert(c.id.clone(), c);
            return Ok(r);
        }
        let c = self
            .calls
            .get(text(input, "id"))
            .ok_or_else(|| ApiError::new(404, "Call is no longer ringing"))?;
        if action == "cancel" && c.from == account
            || ["accept", "decline"].contains(&action) && c.to == account
        {
            let c = self.calls.remove(text(input, "id")).unwrap();
            return Ok(json!({"ok":true,"channel":c.channel}));
        }
        Err(ApiError::new(403, "Call action unavailable"))
    }
    fn roles(&mut self, account: &str, input: &Value) -> ApiResult<Value> {
        if !self.state.can_manage_roles(account) {
            return Err(ApiError::new(403, "Manage roles permission required"));
        }
        let roles: Vec<Role> = serde_json::from_value(input["roles"].clone())?;
        let base: Set<String> = serde_json::from_value(input["everyonePermissions"].clone())?;
        let known: Set<String> = ["view", "send", "connect", "manage_roles"]
            .map(str::to_owned)
            .into();
        let base_known: Set<String> = ["view", "send", "connect"].map(str::to_owned).into();
        let mut ids = Set::new();
        if roles.len() > 100 || !base.is_subset(&base_known) {
            return Err(ApiError::new(400, "Invalid role configuration"));
        }
        for r in &roles {
            if !valid_id(&r.id)
                || !ids.insert(r.id.clone())
                || !valid_name(&r.name, 40)
                || !r.permissions.is_subset(&known)
                || r.members.len() > 1000
                || r.members
                    .iter()
                    .any(|a| !self.state.accounts.contains_key(a))
            {
                return Err(ApiError::new(400, "Invalid role"));
            }
        }
        if !self.state.admins.contains(account) {
            let top = self
                .state
                .highest_role(account)
                .ok_or_else(|| ApiError::new(403, "Cannot change higher roles"))?;
            let protected = self.state.roles.len() - top;
            if roles.len() < protected || base != self.state.everyone_permissions {
                return Err(ApiError::new(
                    403,
                    "Cannot change everyone permissions or higher roles",
                ));
            }
            let new_top = roles.len() - protected;
            if self.state.roles[top..] != roles[new_top..] {
                return Err(ApiError::new(
                    403,
                    "Cannot change your role or higher roles",
                ));
            }
            let old: Map<_, _> = self.state.roles[..top]
                .iter()
                .map(|r| (r.id.clone(), r))
                .collect();
            let changed: Map<_, _> = roles[..new_top].iter().map(|r| (r.id.clone(), r)).collect();
            let all: Set<_> = old.keys().chain(changed.keys()).collect();
            let own = self.state.global_permissions(account);
            for id in all {
                let before = old.get(id);
                let after = changed.get(id);
                if before == after {
                    continue;
                }
                if after.is_some_and(|r| !r.permissions.is_subset(&own)) {
                    return Err(ApiError::new(
                        403,
                        "Cannot grant permissions you do not have",
                    ));
                }
                let empty = Set::new();
                let b = before.map(|r| &r.members).unwrap_or(&empty);
                let a = after.map(|r| &r.members).unwrap_or(&empty);
                for member in b.symmetric_difference(a) {
                    if member == account
                        || self.state.admins.contains(member)
                        || self.state.highest_role(member).is_some_and(|i| i >= top)
                    {
                        return Err(ApiError::new(
                            403,
                            "Cannot change roles of an equal or higher member",
                        ));
                    }
                }
            }
        }
        self.state.roles = roles;
        self.state.everyone_permissions = base;
        for c in &mut self.state.channels {
            c.role_overrides
                .retain(|id, _| id == "everyone" || ids.contains(id));
        }
        self.save()?;
        Ok(json!({"ok":true}))
    }
    fn admin(&mut self, account: &str, input: &Value) -> ApiResult<Value> {
        if !self.state.admins.contains(account) {
            return Err(ApiError::new(403, "Administrator access required"));
        }
        let name = text(input, "name").trim();
        let description = text(input, "description");
        let image = text(input, "image");
        if !valid_name(name, 60) || description.chars().count() > 500 {
            return Err(ApiError::new(400, "Invalid server details"));
        }
        validate_image(image, 10000)?;
        let channels: Vec<Channel> = serde_json::from_value(input["channels"].clone())?;
        if channels.len() > 100 {
            return Err(ApiError::new(400, "Too many channels"));
        }
        let mut ids = Set::new();
        for c in &channels {
            if !valid_id(&c.id)
                || !ids.insert(c.id.clone())
                || !valid_name(&c.name, 40)
                || !["text", "voice"].contains(&c.kind.as_str())
                || c.allow.len() > 1000
                || c.allow.iter().any(|a| !valid_hash(a))
                || !(500..=6000).contains(&c.bit_rate)
                || !(2..=32).contains(&c.max_members)
                || c.slow_mode_seconds > 3600
                || c.role_overrides.len() > 101
            {
                return Err(ApiError::new(400, "Invalid channel configuration"));
            }
            if self
                .state
                .channels
                .iter()
                .any(|o| o.id == c.id && o.kind != c.kind)
            {
                return Err(ApiError::new(400, "Cannot change channel type"));
            }
            for (role, permissions) in &c.role_overrides {
                if role != "everyone" && !self.state.roles.iter().any(|r| r.id == *role)
                    || permissions
                        .keys()
                        .any(|p| !["view", "send", "connect"].contains(&p.as_str()))
                {
                    return Err(ApiError::new(400, "Invalid role override"));
                }
            }
        }
        for f in &self.state.friendships {
            for id in [&f.text_channel, &f.voice_channel] {
                if self.state.channels.iter().find(|c| &c.id == id)
                    != channels.iter().find(|c| &c.id == id)
                {
                    return Err(ApiError::new(
                        400,
                        "Friend channels are managed by their participants",
                    ));
                }
            }
        }
        self.state.name = name.into();
        self.state.description = description.into();
        self.state.image = image.into();
        self.state.channels = channels;
        self.save()?;
        Ok(json!({"ok":true}))
    }
    fn voice(&mut self, account: &str, input: &Value, now: u64) -> ApiResult<Value> {
        self.rate(&format!("voice-control/{account}"), 600, now)?;
        let c = self.channel(text(input, "channel"), account, "voice")?;
        if !self.state.permits(&c, account, "connect") {
            return Err(ApiError::new(403, "Voice access is not permitted"));
        }
        let action = text(input, "action");
        if action == "leave" {
            if self
                .presence
                .get(account)
                .is_some_and(|p| p.channel == c.id)
            {
                self.leave_voice(account);
            }
            return Ok(json!({"ok":true}));
        }
        if action == "media" {
            if !self
                .presence
                .get(account)
                .is_some_and(|p| p.channel == c.id)
                || !self.floors.get(&c.id).is_some_and(|f| f.account == account)
            {
                return Err(ApiError::new(403, "Push-to-talk floor unavailable"));
            }
            let e: Envelope = serde_json::from_value(input["envelope"].clone())?;
            if !valid_id(&e.id)
                || e.server != self.state.id
                || e.channel != c.id
                || e.author != account
                || e.kind != "voice"
                || e.cipher.len() > 48000
                || e.signature.len() > 10000
                || now.abs_diff(e.time) > 12000
                || !crypto::verify(
                    &self.state.accounts[account].public_key,
                    &e.signed_bytes(),
                    &e.signature,
                )?
            {
                return Err(ApiError::new(400, "Invalid voice frame"));
            }
            if e.key_revision != self.state.channel_keys[&c.id].revision {
                return Err(ApiError::new(
                    409,
                    "Voice encryption changed; refresh the channel key",
                ));
            }
            if self
                .voice_frames
                .get(&c.id)
                .is_some_and(|frames| frames.iter().any(|f| f.envelope.id == e.id))
            {
                return Ok(json!({"ok":true}));
            }
            self.rate(&format!("voice/{account}"), 240, now)?;
            let frames = self.voice_frames.entry(c.id).or_default();
            if frames.len() == 6 {
                frames.pop_front();
            }
            self.voice_cursor += 1;
            frames.push_back(VoiceFrame {
                envelope: e,
                cursor: self.voice_cursor,
                expires: now + 3000,
            });
            return Ok(json!({"ok":true}));
        }
        if !["join", "heartbeat", "claim", "release"].contains(&action) {
            return Err(ApiError::new(
                400,
                "Update clients to use server-relayed voice",
            ));
        }
        if self
            .presence
            .iter()
            .filter(|(a, p)| a.as_str() != account && p.channel == c.id)
            .count()
            >= c.max_members
        {
            return Err(ApiError::new(409, "Voice channel is full"));
        }
        let joined = self
            .presence
            .get(account)
            .is_some_and(|p| p.channel == c.id);
        if !joined {
            self.leave_voice(account);
        }
        self.presence.insert(
            account.into(),
            Presence {
                channel: c.id.clone(),
                expires: now + 10000,
            },
        );
        if action == "claim" && self.floors.get(&c.id).is_none_or(|f| f.account == account) {
            self.floors.insert(
                c.id.clone(),
                Floor {
                    account: account.into(),
                    expires: now + 3500,
                },
            );
        }
        if action == "release" && self.floors.get(&c.id).is_some_and(|f| f.account == account) {
            self.floors.remove(&c.id);
            self.voice_frames.remove(&c.id);
        }
        let speaker = self
            .floors
            .get(&c.id)
            .map(|f| f.account.as_str())
            .unwrap_or("");
        let after = if joined {
            input["after"].as_i64().unwrap_or(-1)
        } else {
            -1
        };
        let frames: Vec<_> = self
            .voice_frames
            .get(&c.id)
            .into_iter()
            .flatten()
            .filter(|f| {
                after >= 0
                    && f.cursor as i64 > after
                    && f.envelope.author != account
                    && f.envelope.author == speaker
                    && f.envelope.key_revision == self.state.channel_keys[&c.id].revision
            })
            .map(|f| &f.envelope)
            .collect();
        let peers: Vec<_> = self
            .presence
            .iter()
            .filter(|(a, p)| a.as_str() != account && p.channel == c.id)
            .map(|(a, _)| a)
            .collect();
        Ok(
            json!({"transport":"https-relay-v1","peers":peers,"frames":frames,"cursor":self.voice_cursor,"floor":speaker,"bitRate":c.bit_rate,"channelKeys":self.keys_for(&[c])}),
        )
    }
    fn rate(&mut self, key: &str, limit: u32, now: u64) -> ApiResult<()> {
        let r = self.rates.entry(key.into()).or_insert((now, 0));
        if now.saturating_sub(r.0) > 60000 {
            *r = (now, 0);
        }
        r.1 += 1;
        if r.1 > limit {
            return Err(ApiError::new(429, "Too many requests"));
        }
        Ok(())
    }
    pub fn cleanup(&mut self, now: u64) {
        self.challenges.retain(|_, c| c.expires >= now);
        self.joins.retain(|_, j| j.expires >= now);
        self.sessions.retain(|_, s| s.expires >= now);
        self.calls.retain(|_, c| c.expires >= now);
        self.typing.retain(|_, p| p.expires >= now);
        let gone: Vec<_> = self
            .presence
            .iter()
            .filter(|(a, p)| {
                p.expires < now
                    || !self.sessions.values().any(|s| s.account == **a)
                    || !self.state.channels.iter().any(|c| {
                        c.id == p.channel
                            && self.state.permits(c, a, "view")
                            && self.state.permits(c, a, "connect")
                    })
            })
            .map(|(a, _)| a.clone())
            .collect();
        for a in gone {
            self.leave_voice(&a);
        }
        self.video.cleanup(now);
        self.floors.retain(|_, f| f.expires >= now);
        self.voice_frames.retain(|c, frames| {
            if let Some(f) = self.floors.get(c) {
                frames.retain(|frame| frame.expires >= now && frame.envelope.author == f.account);
                !frames.is_empty()
            } else {
                false
            }
        });
        self.rates.retain(|_, r| now.saturating_sub(r.0) <= 120000);
    }
    pub fn ban(&mut self, value: &str, ip: bool) -> Result<()> {
        let normalized = if ip {
            canonical_ip(value)?
        } else {
            value.to_owned()
        };
        let value = normalized.as_str();
        let mut affected: Set<_> = self
            .sessions
            .values()
            .filter(|s| {
                if ip {
                    s.ip == value
                } else {
                    s.account == value
                }
            })
            .map(|s| s.account.clone())
            .collect();
        if ip {
            self.state.banned_ips.insert(value.into());
        } else {
            anyhow::ensure!(valid_hash(value), "Invalid account hash");
            self.state.banned_hashes.insert(value.into());
            affected.insert(value.into());
        }
        self.rotate_all()?;
        self.sessions.retain(|_, s| {
            if ip {
                s.ip != value
            } else {
                s.account != value
            }
        });
        self.calls
            .retain(|_, c| !affected.contains(&c.from) && !affected.contains(&c.to));
        for a in affected {
            self.leave_voice(&a);
        }
        self.save()
    }
    fn leave_voice(&mut self, account: &str) {
        self.video.leave(account);
        self.presence.remove(account);
        self.floors.retain(|c, f| {
            if f.account == account {
                self.voice_frames.remove(c);
                false
            } else {
                true
            }
        });
    }
    pub fn reset_sessions(&mut self) {
        self.sessions.clear();
        self.presence.clear();
        self.floors.clear();
        self.voice_frames.clear();
        self.video.clear();
        self.calls.clear();
        self.challenges.clear();
        self.joins.clear();
        self.pending.clear();
        self.typing.clear();
    }
}
pub fn valid_id(id: &str) -> bool {
    id.len() == 36
        && uuid::Uuid::parse_str(id).is_ok()
        && id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b) || b == b'-')
}
pub fn valid_hash(hash: &str) -> bool {
    hash.len() == 64
        && hash
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
pub fn canonical_ip(value: &str) -> Result<String> {
    let ip = value
        .parse::<std::net::IpAddr>()
        .context("Invalid IP address")?;
    Ok(match ip {
        std::net::IpAddr::V6(ip) => ip
            .to_ipv4_mapped()
            .map(|ip| ip.to_string())
            .unwrap_or_else(|| ip.to_string()),
        ip => ip.to_string(),
    })
}
fn valid_name(name: &str, max: usize) -> bool {
    !name.trim().is_empty() && name.chars().count() <= max && !name.chars().any(char::is_control)
}
pub fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key].as_str().unwrap_or("")
}
fn validate_image(encoded: &str, limit: usize) -> ApiResult<()> {
    let b = crypto::unb64(encoded)?;
    if b.len() > limit || !b.is_empty() && !images::is_webp(&b) {
        return Err(ApiError::new(
            413,
            "Image must be WebP and within its byte limit",
        ));
    }
    Ok(())
}

pub struct RunningServer {
    pub address: SocketAddr,
    stop: Arc<AtomicBool>,
    accept: Option<JoinHandle<()>>,
}
impl RunningServer {
    pub fn start(core: Arc<Mutex<ChatServer>>, bind: &str, port: u16) -> Result<Self> {
        let config = {
            let c = core
                .lock()
                .map_err(|_| anyhow::anyhow!("Server lock poisoned"))?;
            anyhow::ensure!(
                c.state.password_verifier.is_some(),
                "Set a server join password first"
            );
            tls::server(&c.state.tls_store)?
        };
        let listener = TcpListener::bind((bind, port))?;
        let address = listener.local_addr()?;
        listener.set_nonblocking(true)?;
        let stop = Arc::new(AtomicBool::new(false));
        let shutdown = stop.clone();
        let accept = thread::spawn(move || {
            let (tx, rx) = std::sync::mpsc::sync_channel::<TcpStream>(128);
            let rx = Arc::new(Mutex::new(rx));
            let mut workers = Vec::new();
            for _ in 0..16 {
                let rx = rx.clone();
                let core = core.clone();
                let config = config.clone();
                let shutdown = shutdown.clone();
                workers.push(thread::spawn(move || loop {
                    let stream = {
                        match rx.lock() {
                            Ok(rx) => rx.recv(),
                            Err(_) => break,
                        }
                    };
                    match stream {
                        Ok(stream) => {
                            if shutdown.load(Ordering::Relaxed) {
                                break;
                            }
                            if let Err(e) = serve_connection(stream, &core, &config) {
                                eprintln!("VCH connection: {e:#}");
                            }
                        }
                        Err(_) => break,
                    }
                }));
            }
            while !shutdown.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let _ = tx.try_send(stream);
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20))
                    }
                    Err(_) => break,
                }
            }
            drop(tx);
            for w in workers {
                let _ = w.join();
            }
            if let Ok(mut c) = core.lock() {
                c.reset_sessions();
            }
        });
        Ok(Self {
            address,
            stop,
            accept: Some(accept),
        })
    }
    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(t) = self.accept.take() {
            let _ = t.join();
        }
    }
}
impl Drop for RunningServer {
    fn drop(&mut self) {
        self.stop();
    }
}
fn serve_connection(
    socket: TcpStream,
    core: &Arc<Mutex<ChatServer>>,
    config: &Arc<rustls::ServerConfig>,
) -> Result<()> {
    socket.set_nonblocking(false)?;
    socket.set_read_timeout(Some(Duration::from_secs(10)))?;
    socket.set_write_timeout(Some(Duration::from_secs(10)))?;
    let ip = canonical_ip(&socket.peer_addr()?.ip().to_string())?;
    let conn = rustls::ServerConnection::new(config.clone())?;
    let mut input = BufReader::new(rustls::StreamOwned::new(conn, socket));
    let mut budget = 65536;
    let head = http::read_head(&mut input, &mut budget)?;
    let parts: Vec<_> = head.first.split(' ').collect();
    let path = parts
        .get(1)
        .copied()
        .unwrap_or("")
        .strip_prefix("/api/")
        .unwrap_or("");
    let mut admission = None;
    let result = (|| -> ApiResult<Value> {
        if path == "challenge" {
            admission = Some(
                core.lock()
                    .map_err(|_| ApiError::new(503, "Server unavailable"))?
                    .admit_now(&ip)?,
            );
        }
        if parts.len() != 3 || parts[0] != "POST" {
            return Err(ApiError::new(405, "POST required"));
        }
        if head.headers.contains_key("transfer-encoding")
            || !head.headers.contains_key("content-length")
        {
            return Err(ApiError::new(400, "Content-Length required"));
        }
        let count: usize = head.headers["content-length"]
            .parse()
            .map_err(|_| ApiError::new(400, "Invalid content length"))?;
        if count > 700000 {
            return Err(ApiError::new(413, "Request too large"));
        }
        let body = http::read_body(&mut input, &head.headers, 700000, &mut budget)?;
        let value: Value = serde_json::from_slice(&body)?;
        let token = head
            .headers
            .get("authorization")
            .and_then(|s| s.strip_prefix("Bearer "));
        core.lock()
            .map_err(|_| ApiError::new(503, "Server unavailable"))?
            .route(&ip, path, token, &value, admission.as_deref(), crate::now())
    })();
    if let Some(ticket) = admission {
        if let Ok(mut c) = core.lock() {
            if c.pending.get(&ip) == Some(&ticket) {
                c.pending.remove(&ip);
            }
        }
    }
    let (status, value, retry) = match result {
        Ok(v) => (200, v, 0),
        Err(e) => (
            e.status,
            json!({"error":e.message,"retryAfterSeconds":e.retry_after}),
            e.retry_after,
        ),
    };
    let headers = if retry > 0 {
        vec![("Retry-After", retry.to_string())]
    } else {
        Vec::new()
    };
    http::write_reply(
        input.get_mut(),
        status,
        &serde_json::to_vec(&value)?,
        &headers,
    )
}
