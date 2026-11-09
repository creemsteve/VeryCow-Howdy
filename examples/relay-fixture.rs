//! Isolated synthetic browser fixture. Never opens real microphone or camera devices.
use anyhow::Result;
use serde_json::json;
use std::{
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use vch_core::{
    client::ClientCore,
    codec::float_bytes,
    models::*,
    server::{ChatServer, RunningServer},
    vault::Vault,
    voice::{AudioBackend, VoiceBridge, VoiceDriver, VoiceSession},
};
use zeroize::Zeroizing;
fn vault(path: &Path) -> Result<Vault> {
    Vault::open(path, Zeroizing::new("synthetic fixture password".into()))
}
#[derive(Default)]
struct Synthetic {
    recording: bool,
    received: u64,
    phase: usize,
}
impl AudioBackend for Synthetic {
    fn recording(&mut self, v: bool) -> Result<()> {
        self.recording = v;
        Ok(())
    }
    fn read(&mut self, _: u32) -> Result<Option<Vec<u8>>> {
        if !self.recording {
            return Ok(None);
        }
        let pcm = (0..3200)
            .map(|i| {
                ((self.phase + i) as f32 * 2. * std::f32::consts::PI * 440. / 16000.).sin() * 0.2
            })
            .collect::<Vec<_>>();
        self.phase += 3200;
        Ok(Some(float_bytes(&pcm)))
    }
    fn play(&mut self, pcm: &[u8], _: u32) -> Result<()> {
        anyhow::ensure!(pcm.len() == 12800 || !pcm.is_empty(), "Empty audio");
        self.received += 1;
        Ok(())
    }
    fn silence(&mut self) {}
}
fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(String::as_str).unwrap_or("voice");
    let dir = args
        .get(2)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("build/rust-media-fixture"));
    std::fs::create_dir_all(&dir)?;
    let mut server = ChatServer::open(vault(&dir.join("server.vault"))?)?;
    server.set_password(Zeroizing::new("synthetic join password".into()))?;
    server.state.hash_bits = 8;
    let core = Arc::new(Mutex::new(server));
    let _running = RunningServer::start(core.clone(), "127.0.0.1", 0)?;
    let mut a = ClientCore::open(vault(&dir.join("alice.vault"))?)?;
    let mut b = ClientCore::open(vault(&dir.join("bob.vault"))?)?;
    let mut sa = SavedServer {
        address: format!("127.0.0.1:{}", _running.address.port()),
        ..SavedServer::default()
    };
    let mut sb = sa.clone();
    a.connect(
        &mut sa,
        Some(Zeroizing::new("synthetic join password".into())),
    )?;
    std::thread::sleep(Duration::from_millis(10050));
    b.connect(
        &mut sb,
        Some(Zeroizing::new("synthetic join password".into())),
    )?;
    a.poll(&mut sa)?;
    b.poll(&mut sb)?;
    let channel = sa
        .channels
        .iter()
        .find(|c| c.kind == "voice")
        .unwrap()
        .clone();
    let a = Arc::new(Mutex::new(a));
    let b = Arc::new(Mutex::new(b));
    let av = Arc::new(Mutex::new(VoiceSession::new(
        a.clone(),
        sa.clone(),
        channel.clone(),
        "build/models".into(),
    )));
    let bv = Arc::new(Mutex::new(VoiceSession::new(
        b.clone(),
        sb.clone(),
        channel.clone(),
        "build/models".into(),
    )));
    let va = VoiceBridge::start(av.clone())?;
    let vb = VoiceBridge::start(bv.clone())?;
    let mut driver = if mode == "native" {
        av.lock().unwrap().request("/codec", json!({"op":"init"}))?;
        Some(VoiceDriver::new(av.clone(), Synthetic::default())?)
    } else {
        None
    };
    if mode == "video" {
        for v in [&av, &bv] {
            v.lock()
                .unwrap()
                .request("/signal", json!({"action":"join"}))?;
        }
    }
    std::fs::write(
        dir.join("endpoints.json"),
        serde_json::to_vec(&json!({"alice":va.uri(mode=="video"),"bob":vb.uri(mode=="video")}))?,
    )?;
    println!("Rust media fixture ready");
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(180) {
        let command = std::fs::read_to_string(dir.join("command")).unwrap_or_default();
        if command == "stop" || dir.join("stop").exists() {
            break;
        }
        if mode == "video" {
            for v in [&av, &bv] {
                v.lock()
                    .unwrap()
                    .request("/signal", json!({"action":"heartbeat"}))?;
            }
        }
        if let Some(driver) = driver.as_mut() {
            driver.step(command.trim() == "press")?;
            let state = json!({"ready":true,"connected":driver.participants,"recording":driver.audio.recording,"received":driver.audio.received});
            let mut temp = tempfile::NamedTempFile::new_in(&dir)?;
            std::io::Write::write_all(&mut temp, &serde_json::to_vec(&state)?)?;
            temp.persist(dir.join("state.json"))?;
        }
        std::thread::sleep(Duration::from_millis(if mode == "native" {
            150
        } else {
            500
        }));
    }
    drop(driver);
    drop(va);
    drop(vb);
    Ok(())
}
