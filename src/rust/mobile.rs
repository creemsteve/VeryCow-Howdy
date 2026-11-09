//! Native mobile adapter over the current client, vault and media implementation.
//! The UI receives display state, never identity secrets or channel/password keys.
use crate::{
    client::ClientCore, crypto, models::*, server::text, vault::Vault, voice::VoiceSession,
};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
};
use zeroize::Zeroizing;

pub struct MobileClient {
    core: Arc<Mutex<ClientCore>>,
    models: PathBuf,
    voice: Option<VoiceSession>,
}
impl MobileClient {
    pub fn open(directory: PathBuf, password: Zeroizing<String>, models: PathBuf) -> Result<Self> {
        Ok(Self {
            core: Arc::new(Mutex::new(ClientCore::open(Vault::open(
                directory.join("data.vault"),
                password,
            )?)?)),
            models,
            voice: None,
        })
    }
    pub fn request(&mut self, path: &str, input: Value) -> Result<Value> {
        anyhow::ensure!(input.is_object(), "JSON object required");
        if path == "/voice/close" {
            self.voice = None;
            return Ok(json!({}));
        }
        if path == "/remove" {
            self.voice = None;
        }
        if let Some(route) = path.strip_prefix("/media") {
            return self
                .voice
                .as_mut()
                .context("Join voice first")?
                .request(route, input);
        }
        let mut core = self
            .core
            .lock()
            .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
        if path == "/state" {
            return snapshot(&core);
        }
        if path == "/image" {
            let limit = input["profile"].as_bool().unwrap_or(false);
            let bytes = crypto::unb64(text(&input, "data"))?;
            return Ok(
                json!({"data":crypto::b64(&crate::images::prepare_bytes(&bytes,if limit {10000} else {100000},if limit {256} else {1600})?)}),
            );
        }
        if path == "/join" {
            let mut server = SavedServer {
                address: text(&input, "address").into(),
                tor_only: input["torOnly"].as_bool().unwrap_or(false),
                tor_socks_port: port(&input)?,
                ..SavedServer::default()
            };
            core.connect(
                &mut server,
                Some(Zeroizing::new(text(&input, "password").into())),
            )?;
            return Ok(json!({"server":server.id}));
        }
        if path == "/profile" {
            let name = text(&input, "name").trim();
            let bio = text(&input, "bio");
            let pronouns = text(&input, "pronouns");
            anyhow::ensure!(
                !name.is_empty()
                    && name.chars().count() <= 40
                    && bio.chars().count() <= 240
                    && pronouns.chars().count() <= 40,
                "Name 1–40; bio ≤240; pronouns ≤40 characters"
            );
            let avatar = text(&input, "avatar");
            if !avatar.is_empty() {
                crate::images::decode(&crypto::unb64(avatar)?, 256)?;
                anyhow::ensure!(
                    crypto::unb64(avatar)?.len() <= 10000,
                    "Avatar exceeds 10 KB"
                );
            }
            core.state.name = name.into();
            core.state.bio = bio.into();
            core.state.pronouns = pronouns.into();
            core.state.avatar = avatar.into();
            core.save()?;
            for s in core.state.servers.clone() {
                if core.connected(&s) {
                    core.update_profile(&s)?;
                }
            }
            return Ok(json!({}));
        }
        if path == "/audio" {
            let number = |key: &str, min, max| -> Result<u32> {
                let n = input[key].as_u64().context("Invalid audio setting")?;
                anyhow::ensure!((min..=max).contains(&n), "Audio setting out of range");
                Ok(n as u32)
            };
            core.state.input_gain = number("inputGain", 0, 300)?;
            core.state.output_gain = number("outputGain", 0, 300)?;
            core.state.outgoing_bit_rate = number("outgoingBitRate", 500, 6000)?;
            core.state.microphone = text(&input, "microphone").into();
            core.state.speakers = text(&input, "speakers").into();
            core.state.dark_mode = input["darkMode"].as_bool().unwrap_or(true);
            core.state.ptt_key = text(&input, "pttKey").into();
            core.save()?;
            return Ok(json!({}));
        }
        let mut s = core
            .state
            .servers
            .iter()
            .find(|s| s.id == text(&input, "server"))
            .cloned()
            .context("Select a saved server")?;
        match path {
            "/connect" => {
                core.connect(
                    &mut s,
                    input["password"].as_str().map(|p| Zeroizing::new(p.into())),
                )?;
                Ok(json!({}))
            }
            "/disconnect" => {
                core.disconnect(&s);
                Ok(json!({}))
            }
            "/connection" => {
                core.connection_settings(
                    &mut s,
                    input["torOnly"].as_bool().unwrap_or(false),
                    port(&input)?,
                )?;
                Ok(json!({}))
            }
            "/remove" => {
                core.disconnect(&s);
                core.state.servers.retain(|old| old.id != s.id);
                core.save()?;
                Ok(json!({}))
            }
            "/poll" => Ok(json!({"changed":core.poll(&mut s)?})),
            "/send" => {
                let channel = s
                    .channels
                    .iter()
                    .find(|c| c.id == text(&input, "channel"))
                    .cloned()
                    .context("Select a channel")?;
                core.send(
                    &mut s,
                    &channel,
                    text(&input, "kind"),
                    &crypto::unb64(text(&input, "data"))?,
                )?;
                Ok(json!({}))
            }
            "/decrypt" => {
                let e = s
                    .messages
                    .iter()
                    .find(|e| e.id == text(&input, "message"))
                    .context("Message unavailable")?;
                Ok(json!({"data":crypto::b64(&core.decrypt(&s,e)?)}))
            }
            "/request" => {
                let route = text(&input, "route");
                anyhow::ensure!(
                    ["profile", "typing", "friend", "call", "roles", "admin", "keys"]
                        .contains(&route),
                    "Unsupported server operation"
                );
                core.request(&s, route, &input["data"], true)
            }
            "/voice/open" => {
                let channel = s
                    .channels
                    .iter()
                    .find(|c| c.id == text(&input, "channel") && c.kind == "voice")
                    .cloned()
                    .context("Select a voice channel")?;
                anyhow::ensure!(core.connected(&s), "Reconnect before joining voice");
                drop(core);
                self.voice = None;
                let mut voice =
                    VoiceSession::new(self.core.clone(), s, channel, self.models.clone());
                voice.request("/codec", json!({"op":"init"}))?;
                let out = voice.request("/signal", json!({"action":"join"}))?;
                self.voice = Some(voice);
                Ok(out)
            }
            _ => bail!("Unknown mobile operation"),
        }
    }
}
fn port(input: &Value) -> Result<u16> {
    let n = input["torSocksPort"].as_u64().unwrap_or(9050);
    anyhow::ensure!((1..=65535).contains(&n), "Tor SOCKS port must be 1–65535");
    Ok(n as u16)
}
fn snapshot(core: &ClientCore) -> Result<Value> {
    let mut state = serde_json::to_value(&core.state)?;
    let object = state.as_object_mut().context("Invalid client state")?;
    object.remove("privateKey");
    object.remove("publicKey");
    for s in state["servers"].as_array_mut().context("Invalid servers")? {
        let saved = core
            .state
            .servers
            .iter()
            .find(|old| old.id == text(s, "id"))
            .context("Missing server")?;
        let o = s.as_object_mut().context("Invalid server")?;
        for key in ["passwordKey", "channelKeys", "previousChannelKeys"] {
            o.remove(key);
        }
        o.insert("connected".into(), json!(core.connected(saved)));
        o.insert("account".into(), json!(core.account(saved)));
        o.insert(
            "calls".into(),
            json!(core
                .incoming_calls
                .get(&saved.id)
                .cloned()
                .unwrap_or_default()),
        );
        o.insert(
            "typing".into(),
            json!(core.typing.get(&saved.id).cloned().unwrap_or_default()),
        );
    }
    Ok(state)
}
