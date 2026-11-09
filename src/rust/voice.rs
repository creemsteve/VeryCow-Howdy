use crate::{client::ClientCore, codec::DacCodec, crypto, models::*, server::text};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::VecDeque,
    path::PathBuf,
    sync::{Arc, Mutex},
};
#[cfg(feature = "audio")]
use std::{
    sync::atomic::{AtomicBool, Ordering},
    thread::{self, JoinHandle},
    time::Duration,
};
pub struct VoiceSession {
    core: Arc<Mutex<ClientCore>>,
    pub server: SavedServer,
    pub channel: Channel,
    models: PathBuf,
    codec: Option<DacCodec>,
    received: VecDeque<String>,
    floor: String,
    floor_at: u64,
    cursor: i64,
    closed: bool,
}
impl VoiceSession {
    pub fn new(
        core: Arc<Mutex<ClientCore>>,
        server: SavedServer,
        channel: Channel,
        models: PathBuf,
    ) -> Self {
        Self {
            core,
            server,
            channel,
            models,
            codec: None,
            received: VecDeque::new(),
            floor: String::new(),
            floor_at: 0,
            cursor: -1,
            closed: false,
        }
    }
    fn codec(&mut self, input: &Value) -> Result<Value> {
        if self.codec.is_none() {
            self.codec = Some(DacCodec::open(&self.models)?);
        }
        self.codec.as_mut().unwrap().request(input)
    }
    pub fn request(&mut self, path: &str, mut input: Value) -> Result<Value> {
        anyhow::ensure!(!self.closed, "Voice session ended");
        anyhow::ensure!(input.is_object(), "JSON object required");
        if let Some(action) = path.strip_prefix("/video/") {
            return self.video(action, input);
        }
        match path {
            "/settings" => {
                let c = self
                    .core
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
                Ok(
                    json!({"account":c.account(&self.server),"channel":self.channel.name,"input":c.state.microphone,"output":c.state.speakers,"inputGain":c.state.input_gain,"outputGain":c.state.output_gain,"ptt":c.state.ptt_key}),
                )
            }
            "/signal" | "/media" => {
                input["channel"] = json!(self.channel.id);
                input["after"] = json!(self.cursor);
                if path == "/media" {
                    input["action"] = json!("media");
                }
                let mut c = self
                    .core
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
                let out = c.request(&self.server, "voice", &input, true)?;
                if path == "/signal" && text(&input, "action") != "leave" {
                    anyhow::ensure!(
                        text(&out, "transport") == "https-relay-v1",
                        "Unsupported voice transport"
                    );
                    c.accept_channel_keys(&mut self.server, &out["channelKeys"])?;
                    self.cursor = out["cursor"].as_i64().context("Invalid voice cursor")?;
                    self.floor = text(&out, "floor").into();
                    self.floor_at = crate::now();
                }
                Ok(out)
            }
            "/codec" => self.codec(&input),
            "/encode" => {
                let account = self
                    .core
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Client unavailable"))?
                    .account(&self.server);
                anyhow::ensure!(
                    self.floor == account && crate::now().saturating_sub(self.floor_at) <= 3000,
                    "Push-to-talk floor unavailable"
                );
                input["op"] = json!("encode");
                let rate = self
                    .core
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Client unavailable"))?
                    .state
                    .outgoing_bit_rate;
                input["bitRate"] = json!(self.channel.bit_rate.min(rate.max(500)));
                let encoded = self.codec(&input)?;
                let e = self
                    .core
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Client unavailable"))?
                    .envelope(
                        &self.server,
                        &self.channel.id,
                        "voice",
                        &crypto::unb64(text(&encoded, "data"))?,
                    )?;
                Ok(serde_json::to_value(e)?)
            }
            "/decode" => {
                let e: Envelope = serde_json::from_value(input["envelope"].clone())?;
                anyhow::ensure!(
                    e.kind == "voice"
                        && e.channel == self.channel.id
                        && e.author == text(&input, "author")
                        && e.author == self.floor
                        && crate::now().abs_diff(e.time) <= 12000,
                    "Invalid voice frame"
                );
                let data = self.decrypt_live(&e)?;
                self.codec(&json!({"op":"decode","data":crypto::b64(&data)}))
            }
            _ => bail!("Unknown voice endpoint"),
        }
    }
    fn decrypt_live(&mut self, e: &Envelope) -> Result<Vec<u8>> {
        anyhow::ensure!(!self.received.contains(&e.id), "Replayed media frame");
        let mut core = self
            .core
            .lock()
            .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
        if e.key_revision
            > self
                .server
                .key_revisions
                .get(&self.channel.id)
                .copied()
                .unwrap_or(0)
        {
            core.refresh_channel_keys(&mut self.server)?;
        }
        anyhow::ensure!(
            e.key_revision >= 1
                && Some(&e.key_revision) == self.server.key_revisions.get(&self.channel.id),
            "Media encryption changed"
        );
        let plain = core.decrypt(&self.server, e)?;
        self.received.push_back(e.id.clone());
        if self.received.len() > 1024 {
            self.received.pop_front();
        }
        Ok(plain)
    }
    fn video(&mut self, action: &str, mut input: Value) -> Result<Value> {
        match action {
            "encode" => {
                let payload = crypto::unb64(text(&input, "data"))?;
                anyhow::ensure!(payload.len() <= 125000, "Video frame too large");
                let stream = text(&input, "stream");
                anyhow::ensure!(crate::server::valid_id(stream), "Invalid video stream");
                let e = self
                    .core
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Client unavailable"))?
                    .envelope(
                        &self.server,
                        &self.channel.id,
                        &format!("video/{stream}"),
                        &payload,
                    )?;
                Ok(serde_json::to_value(e)?)
            }
            "decode" => {
                let e: Envelope = serde_json::from_value(input["envelope"].clone())?;
                anyhow::ensure!(
                    e.kind == format!("video/{}", text(&input, "stream"))
                        && e.channel == self.channel.id
                        && e.author == text(&input, "author")
                        && crate::now().abs_diff(e.time) <= 12000,
                    "Invalid video frame"
                );
                Ok(json!({"data":crypto::b64(&self.decrypt_live(&e)?)}))
            }
            "start" | "stop" | "configure" | "poll" | "media" => {
                input["channel"] = json!(self.channel.id);
                input["action"] = json!(action);
                self.core
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Client unavailable"))?
                    .request(&self.server, "video", &input, true)
            }
            _ => bail!("Unknown video action"),
        }
    }
    pub fn close(&mut self) {
        if !self.closed {
            self.closed = true;
            self.codec = None;
            if let Ok(mut core) = self.core.lock() {
                let _ = core.request(
                    &self.server,
                    "voice",
                    &json!({"channel":self.channel.id,"action":"leave"}),
                    true,
                );
            }
        }
    }
}
impl Drop for VoiceSession {
    fn drop(&mut self) {
        self.close();
    }
}

pub trait AudioBackend {
    fn allowed(&self) -> bool {
        true
    }
    fn recording(&mut self, active: bool) -> Result<()>;
    fn read(&mut self, gain: u32) -> Result<Option<Vec<u8>>>;
    fn play(&mut self, pcm: &[u8], gain: u32) -> Result<()>;
    fn silence(&mut self);
}
#[cfg(feature = "audio")]
impl AudioBackend for crate::audio::Devices {
    fn allowed(&self) -> bool {
        self.allowed()
    }
    fn recording(&mut self, active: bool) -> Result<()> {
        self.recording(active)
    }
    fn read(&mut self, gain: u32) -> Result<Option<Vec<u8>>> {
        self.read(gain)
    }
    fn play(&mut self, pcm: &[u8], gain: u32) -> Result<()> {
        self.play(pcm, gain)
    }
    fn silence(&mut self) {
        self.silence();
    }
}
pub struct VoiceDriver<A: AudioBackend> {
    pub session: Arc<Mutex<VoiceSession>>,
    pub audio: A,
    pub transmitting: bool,
    pub participants: usize,
    pressed: bool,
    input_gain: u32,
    output_gain: u32,
}
impl<A: AudioBackend> VoiceDriver<A> {
    pub fn new(session: Arc<Mutex<VoiceSession>>, audio: A) -> Result<Self> {
        let settings = session
            .lock()
            .map_err(|_| anyhow::anyhow!("Voice unavailable"))?
            .request("/settings", json!({}))?;
        Ok(Self {
            session,
            audio,
            transmitting: false,
            participants: 0,
            pressed: false,
            input_gain: settings["inputGain"].as_u64().unwrap_or(100) as u32,
            output_gain: settings["outputGain"].as_u64().unwrap_or(100) as u32,
        })
    }
    pub fn step(&mut self, pressed: bool) -> Result<()> {
        if !pressed {
            self.audio.recording(false)?;
            self.transmitting = false;
        }
        let result = self.step_inner(pressed);
        if result.is_err() {
            let _ = self.audio.recording(false);
            self.audio.silence();
            self.transmitting = false;
        }
        result
    }
    fn step_inner(&mut self, pressed: bool) -> Result<()> {
        let action = if pressed {
            "claim"
        } else if self.pressed {
            "release"
        } else {
            "heartbeat"
        };
        self.pressed = pressed;
        let mut v = self
            .session
            .lock()
            .map_err(|_| anyhow::anyhow!("Voice unavailable"))?;
        let account = {
            let c = v
                .core
                .lock()
                .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
            c.account(&v.server)
        };
        let state = v.request("/signal", json!({"action":action}))?;
        self.participants = state["peers"]
            .as_array()
            .context("Invalid voice roster")?
            .len();
        self.transmitting = pressed && text(&state, "floor") == account;
        self.audio.recording(self.transmitting)?;
        if self.transmitting {
            self.audio.silence();
            if let Some(pcm) = self.audio.read(self.input_gain)? {
                let e = v.request("/encode", json!({"data":crypto::b64(&pcm)}))?;
                if self.audio.allowed() {
                    v.request("/media", json!({"envelope":e}))?;
                } else {
                    self.audio.recording(false)?;
                    self.transmitting = false;
                }
            }
        } else {
            let frames: Vec<Envelope> = serde_json::from_value(state["frames"].clone())?;
            for e in frames {
                let pcm = v.request("/decode", json!({"author":e.author,"envelope":e}))?;
                self.audio
                    .play(&crypto::unb64(text(&pcm, "data"))?, self.output_gain)?;
            }
        }
        Ok(())
    }
    pub fn stop(&mut self) {
        let _ = self.audio.recording(false);
        self.audio.silence();
        self.transmitting = false;
        if let Ok(mut s) = self.session.lock() {
            s.close();
        }
    }
}
impl<A: AudioBackend> Drop for VoiceDriver<A> {
    fn drop(&mut self) {
        self.stop();
    }
}

#[cfg(feature = "audio")]
pub struct NativeVoice {
    pub session: Arc<Mutex<VoiceSession>>,
    pressed: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
    pub status: Arc<Mutex<String>>,
    thread: Option<JoinHandle<()>>,
}
#[cfg(feature = "audio")]
impl NativeVoice {
    pub fn start(session: Arc<Mutex<VoiceSession>>) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let pressed = Arc::new(AtomicBool::new(false));
        let status = Arc::new(Mutex::new("Loading voice…".into()));
        let shutdown = stop.clone();
        let ptt = pressed.clone();
        let message = status.clone();
        let voice = session.clone();
        let thread = thread::spawn(move || {
            let result = (|| -> Result<()> {
                let settings = voice
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Voice unavailable"))?
                    .request("/settings", json!({}))?;
                voice
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Voice unavailable"))?
                    .request("/codec", json!({"op":"init"}))?;
                let devices = crate::audio::Devices::open_gated(
                    text(&settings, "input"),
                    text(&settings, "output"),
                    Some(ptt.clone()),
                )?;
                let mut driver = VoiceDriver::new(voice.clone(), devices)?;
                while !shutdown.load(Ordering::Relaxed) {
                    driver.step(ptt.load(Ordering::Relaxed))?;
                    if let Ok(mut m) = message.lock() {
                        *m = format!(
                            "{} · {} other participant(s)",
                            if driver.transmitting {
                                "Microphone live"
                            } else {
                                "Ready — hold to talk"
                            },
                            driver.participants
                        );
                    }
                    thread::sleep(Duration::from_millis(150));
                }
                driver.stop();
                Ok(())
            })();
            if let Err(err) = result {
                if let Ok(mut m) = message.lock() {
                    *m = err.to_string();
                }
                if let Ok(mut s) = voice.lock() {
                    s.close();
                }
            }
        });
        Self {
            session,
            pressed,
            stop,
            status,
            thread: Some(thread),
        }
    }
    pub fn press(&self, pressed: bool) {
        self.pressed.store(pressed, Ordering::Relaxed);
    }
    pub fn stop(&mut self) {
        self.pressed.store(false, Ordering::Relaxed);
        self.stop.store(true, Ordering::Relaxed);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}
#[cfg(feature = "audio")]
impl Drop for NativeVoice {
    fn drop(&mut self) {
        self.stop();
    }
}
