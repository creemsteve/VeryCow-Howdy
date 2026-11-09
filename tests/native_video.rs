#![cfg(feature = "desktop")]
use anyhow::Result;
use image::{Rgb, RgbImage};
use openh264::{decoder::Decoder, encoder::Encoder};
use serde_json::json;
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};
use vch_core::{
    client::ClientCore,
    models::SavedServer,
    native_video::{decode_frame, encode_frame, Capture, VideoDriver},
    server::{ChatServer, RunningServer},
    vault::Vault,
    voice::VoiceSession,
};
use zeroize::Zeroizing;

struct Synthetic {
    stopped: Arc<AtomicBool>,
}
impl Capture for Synthetic {
    fn frame(&mut self) -> Result<RgbImage> {
        Ok(RgbImage::from_pixel(96, 64, Rgb([180, 70, 30])))
    }
}
impl Drop for Synthetic {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::Relaxed);
    }
}
fn source(stopped: &Arc<AtomicBool>) -> Box<dyn Capture> {
    Box::new(Synthetic {
        stopped: stopped.clone(),
    })
}
fn vault(path: impl AsRef<std::path::Path>) -> Vault {
    Vault::open(path, Zeroizing::new("native video fixture password".into())).unwrap()
}

#[test]
fn native_avc_roundtrip_and_malformed_packets() {
    let image = RgbImage::from_pixel(96, 64, Rgb([180, 70, 30]));
    let mut encoder = Encoder::new().unwrap();
    let bytes = encode_frame(&mut encoder, &image, 1000).unwrap().unwrap();
    let mut decoder = Decoder::new().unwrap();
    let output = decode_frame(&mut decoder, &bytes).unwrap().unwrap();
    assert_eq!(output.dimensions(), image.dimensions());
    for (actual, expected) in output.get_pixel(30, 30).0.iter().zip([180, 70, 30]) {
        assert!((*actual as i32 - expected).abs() < 8);
    }
    for packet in [vec![], vec![0; 4], vec![255; 10], vec![0; 125001]] {
        assert!(decode_frame(&mut decoder, &packet).is_err());
    }
    let n = u32::from_be_bytes(bytes[..4].try_into().unwrap()) as usize;
    let mut header: serde_json::Value = serde_json::from_slice(&bytes[4..n + 4]).unwrap();
    for (field, value) in [
        ("codec", json!("vp8")),
        ("width", json!(9000)),
        ("height", json!(1)),
        ("width", json!(94)),
    ] {
        let old = header[field].clone();
        header[field] = value;
        let changed = serde_json::to_vec(&header).unwrap();
        let mut packet = (changed.len() as u32).to_be_bytes().to_vec();
        packet.extend(changed);
        packet.extend(&bytes[n + 4..]);
        assert!(decode_frame(&mut Decoder::new().unwrap(), &packet).is_err());
        header[field] = old;
    }
    assert!(encode_frame(&mut encoder, &RgbImage::new(95, 64), 0).is_err());
}

#[test]
fn native_video_two_sources_opt_in_bitrate_and_failure_cleanup() {
    let dir = tempfile::tempdir().unwrap();
    let mut server = ChatServer::open(vault(dir.path().join("server.vault"))).unwrap();
    server.state.hash_bits = 8;
    server
        .set_password(Zeroizing::new("native video join password".into()))
        .unwrap();
    let running = RunningServer::start(Arc::new(Mutex::new(server)), "127.0.0.1", 0).unwrap();
    let mut clients = Vec::new();
    for name in ["alice", "bob"] {
        let mut core = ClientCore::open(vault(dir.path().join(format!("{name}.vault")))).unwrap();
        let mut saved = SavedServer {
            address: running.address.to_string(),
            ..Default::default()
        };
        core.connect(
            &mut saved,
            Some(Zeroizing::new("native video join password".into())),
        )
        .unwrap();
        let channel = saved
            .channels
            .iter()
            .find(|c| c.kind == "voice")
            .unwrap()
            .clone();
        let session = Arc::new(Mutex::new(VoiceSession::new(
            Arc::new(Mutex::new(core)),
            saved,
            channel,
            "build/models".into(),
        )));
        session
            .lock()
            .unwrap()
            .request("/signal", json!({"action":"join"}))
            .unwrap();
        clients.push(session);
        if name == "alice" {
            std::thread::sleep(Duration::from_millis(10100));
        }
    }
    // Refresh membership after the server's admission cooldown.
    for session in &clients {
        session
            .lock()
            .unwrap()
            .request("/signal", json!({"action":"heartbeat"}))
            .unwrap();
    }
    let mut alice = VideoDriver::new(clients[0].clone()).unwrap();
    let mut bob = VideoDriver::new(clients[1].clone()).unwrap();
    let camera = Arc::new(AtomicBool::new(false));
    let screen = Arc::new(AtomicBool::new(false));
    assert!(alice
        .start("invalid", 500, source(&Arc::new(AtomicBool::new(false))))
        .is_err());
    assert!(alice
        .start("camera", 49, source(&Arc::new(AtomicBool::new(false))))
        .is_err());
    alice.start("camera", 500, source(&camera)).unwrap();
    alice.start("screen", 500, source(&screen)).unwrap();
    alice.step().unwrap();
    bob.step().unwrap();
    assert_eq!(bob.streams.len(), 2);
    assert!(bob
        .streams
        .iter()
        .all(|s| !s.receiving && s.image.is_none()));
    for s in bob.streams.clone() {
        bob.receive(&s.id, true);
    }
    alice.step().unwrap();
    bob.step().unwrap();
    assert!(bob.streams.iter().all(|s| s.receiving && s.image.is_some()));
    assert!(bob
        .streams
        .iter()
        .any(|s| s.image.as_ref().unwrap().dimensions() == (960, 540)));
    assert!(bob
        .streams
        .iter()
        .any(|s| s.image.as_ref().unwrap().dimensions() == (640, 360)));
    alice.configure("camera", 250).unwrap();
    bob.step().unwrap();
    assert_eq!(
        bob.streams
            .iter()
            .find(|s| s.source == "camera")
            .unwrap()
            .kbps,
        250
    );
    for s in bob.streams.clone() {
        bob.receive(&s.id, false);
    }
    alice.step().unwrap();
    bob.step().unwrap();
    assert!(bob
        .streams
        .iter()
        .all(|s| !s.receiving && s.image.is_none()));
    alice.stop("camera");
    assert!(camera.load(Ordering::Relaxed));
    bob.step().unwrap();
    assert_eq!(bob.streams.len(), 1);
    clients[0].lock().unwrap().close();
    assert!(alice.step().is_err());
    assert!(screen.load(Ordering::Relaxed));
    bob.step().unwrap();
    assert!(bob.streams.is_empty());
}
