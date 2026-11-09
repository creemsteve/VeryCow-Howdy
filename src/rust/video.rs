use crate::{
    crypto,
    models::*,
    server::{ApiError, ApiResult},
};
use serde_json::{json, Value};
use std::collections::{BTreeMap, VecDeque};
#[derive(Clone)]
struct Frame {
    envelope: Envelope,
    cursor: u64,
    expires: u64,
}
struct Stream {
    id: String,
    channel: String,
    author: String,
    source: String,
    kbps: u64,
    expires: u64,
    frames: VecDeque<Frame>,
    charges: VecDeque<(u64, usize)>,
    seen: VecDeque<String>,
}
#[derive(Default)]
pub struct VideoRelay {
    streams: BTreeMap<String, Stream>,
    cursor: u64,
}
impl VideoRelay {
    pub fn request(
        &mut self,
        account: &str,
        channel: &Channel,
        state: &ServerState,
        input: &Value,
        now: u64,
    ) -> ApiResult<Value> {
        let action = input["action"].as_str().unwrap_or("");
        let id = input["stream"].as_str().unwrap_or("");
        if action == "start" {
            let source = input["source"].as_str().unwrap_or("");
            if !["screen", "camera"].contains(&source) {
                return Err(ApiError::new(400, "Invalid video source"));
            }
            let kbps = bitrate(input)?;
            if self
                .streams
                .values()
                .any(|s| s.author == account && s.source == source)
            {
                return Err(ApiError::new(
                    409,
                    "Stop the existing stream before starting another",
                ));
            }
            let id = crate::models::id();
            self.streams.insert(
                id.clone(),
                Stream {
                    id: id.clone(),
                    channel: channel.id.clone(),
                    author: account.into(),
                    source: source.into(),
                    kbps,
                    expires: now + 10000,
                    frames: VecDeque::new(),
                    charges: VecDeque::new(),
                    seen: VecDeque::new(),
                },
            );
            return Ok(json!({"stream":id,"kbps":kbps}));
        }
        if action == "poll" {
            let receive = input.get("receive").cloned().unwrap_or(json!([]));
            let receive = receive
                .as_array()
                .ok_or_else(|| ApiError::new(400, "Invalid subscriptions"))?;
            if receive.len() > 64 || receive.iter().any(|v| !v.is_string()) {
                return Err(ApiError::new(
                    400,
                    "Too many or invalid video subscriptions",
                ));
            }
            let after = input["after"].as_i64().unwrap_or(-1);
            let mut listings = Vec::new();
            let mut frames = Vec::new();
            for s in self
                .streams
                .values_mut()
                .filter(|s| s.channel == channel.id)
            {
                if s.author == account {
                    s.expires = now + 10000;
                }
                listings.push(json!({"id":s.id,"author":s.author,"source":s.source,"kbps":s.kbps}));
                if s.author == account || !receive.iter().any(|v| v.as_str() == Some(&s.id)) {
                    continue;
                }
                for f in &s.frames {
                    if f.cursor as i64 > after
                        && f.expires > now
                        && f.envelope.key_revision == state.channel_keys[&channel.id].revision
                    {
                        frames.push(json!({"stream":s.id,"envelope":f.envelope}));
                    }
                }
            }
            return Ok(json!({"streams":listings,"frames":frames,"cursor":self.cursor}));
        }
        let s = self
            .streams
            .get_mut(id)
            .filter(|s| s.author == account && s.channel == channel.id)
            .ok_or_else(|| ApiError::new(403, "Video stream unavailable"))?;
        if action == "stop" {
            self.streams.remove(id);
            return Ok(json!({"ok":true}));
        }
        if action == "configure" {
            s.kbps = bitrate(input)?;
            return Ok(json!({"kbps":s.kbps}));
        }
        if action != "media" {
            return Err(ApiError::new(400, "Unknown video action"));
        }
        let e: Envelope = serde_json::from_value(input["envelope"].clone())?;
        if !crate::server::valid_id(&e.id)
            || e.server != state.id
            || e.channel != channel.id
            || e.author != account
            || e.kind != format!("video/{}", s.id)
            || e.cipher.len() > 166668
            || e.signature.len() > 10000
            || now.abs_diff(e.time) > 12000
            || !crypto::verify(
                &state.accounts[account].public_key,
                &e.signed_bytes(),
                &e.signature,
            )?
        {
            return Err(ApiError::new(400, "Invalid video frame"));
        }
        if e.key_revision != state.channel_keys[&channel.id].revision {
            return Err(ApiError::new(
                409,
                "Video encryption changed; refresh the channel key",
            ));
        }
        if s.seen.contains(&e.id) {
            return Ok(json!({"ok":true}));
        }
        let bytes = crypto::unb64(&e.cipher)?.len();
        while s
            .charges
            .front()
            .is_some_and(|(t, _)| *t <= now.saturating_sub(1000))
        {
            s.charges.pop_front();
        }
        if bytes + s.charges.iter().map(|(_, b)| b).sum::<usize>() > s.kbps as usize * 125 {
            return Err(ApiError::new(429, "Video bitrate limit reached"));
        }
        s.charges.push_back((now, bytes));
        s.seen.push_back(e.id.clone());
        if s.seen.len() > 256 {
            s.seen.pop_front();
        }
        if s.frames.len() == 12 {
            s.frames.pop_front();
        }
        self.cursor += 1;
        s.frames.push_back(Frame {
            envelope: e,
            cursor: self.cursor,
            expires: now + 1500,
        });
        s.expires = now + 10000;
        Ok(json!({"ok":true}))
    }
    pub fn cleanup(&mut self, now: u64) {
        self.streams.retain(|_, s| s.expires >= now);
        for s in self.streams.values_mut() {
            s.frames.retain(|f| f.expires >= now);
        }
    }
    pub fn leave(&mut self, account: &str) {
        self.streams.retain(|_, s| s.author != account);
    }
    pub fn clear(&mut self) {
        self.streams.clear();
    }
}
fn bitrate(input: &Value) -> ApiResult<u64> {
    let rate = input
        .get("kbps")
        .map(|v| v.as_u64().unwrap_or(0))
        .unwrap_or(500);
    if !(50..=1000).contains(&rate) {
        return Err(ApiError::new(400, "Video bitrate must be 50–1000 kbps"));
    }
    Ok(rate)
}
