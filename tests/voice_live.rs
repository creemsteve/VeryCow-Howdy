#![cfg(feature = "neural")]
use anyhow::Result;
use serde_json::json;
use std::{
    io::{BufReader, Write},
    net::TcpStream,
    sync::{Arc, Mutex},
    time::Duration,
};
use vch_core::{
    client::ClientCore,
    codec::float_bytes,
    crypto, http,
    models::*,
    server::{ChatServer, RunningServer},
    vault::Vault,
    voice::{AudioBackend, VoiceBridge, VoiceDriver, VoiceSession},
};
use zeroize::Zeroizing;
#[derive(Default)]
struct Synthetic {
    recording: bool,
    played: usize,
    silences: usize,
    block_after_read: bool,
    blocked: bool,
}
impl AudioBackend for Synthetic {
    fn allowed(&self) -> bool {
        !self.blocked
    }
    fn recording(&mut self, v: bool) -> Result<()> {
        self.recording = v;
        Ok(())
    }
    fn read(&mut self, _: u32) -> Result<Option<Vec<u8>>> {
        if self.block_after_read {
            self.blocked = true;
        }
        Ok(self.recording.then(|| {
            float_bytes(
                &(0..3200)
                    .map(|i| (i as f32 * 0.1728).sin() * 0.2)
                    .collect::<Vec<_>>(),
            )
        }))
    }
    fn play(&mut self, pcm: &[u8], _: u32) -> Result<()> {
        assert!(!pcm.is_empty());
        self.played += 1;
        Ok(())
    }
    fn silence(&mut self) {
        self.silences += 1;
    }
}
fn vault(p: impl AsRef<std::path::Path>) -> Vault {
    Vault::open(p, Zeroizing::new("voice fixture password".into())).unwrap()
}
fn bridge_request(
    address: std::net::SocketAddr,
    path: &str,
    token: Option<&str>,
    origin: Option<&str>,
    host: Option<&str>,
) -> http::Reply {
    let mut socket = TcpStream::connect(address).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(20)))
        .unwrap();
    write!(
        socket,
        "{} {path} HTTP/1.1\r\nHost: {}\r\nContent-Length: 2\r\n",
        if token.is_some() { "POST" } else { "GET" },
        host.map(str::to_owned)
            .unwrap_or_else(|| address.to_string())
    )
    .unwrap();
    if let Some(token) = token {
        write!(socket, "X-VCH-Token: {token}\r\n").unwrap();
    }
    if let Some(origin) = origin {
        write!(socket, "Origin: {origin}\r\n").unwrap();
    }
    socket.write_all(b"\r\n{}").unwrap();
    socket.flush().unwrap();
    http::read_reply(&mut BufReader::new(socket)).unwrap()
}
#[cfg(feature = "neural")]
#[test]
fn native_voice_sessions_signatures_replay_and_bridge_security() {
    let dir = tempfile::tempdir_in("target").unwrap();
    let mut s = ChatServer::open(vault(dir.path().join("server.vault"))).unwrap();
    s.set_password(Zeroizing::new("voice join password".into()))
        .unwrap();
    s.state.hash_bits = 8;
    let server = Arc::new(Mutex::new(s));
    let _running = RunningServer::start(server.clone(), "127.0.0.1", 0).unwrap();
    let mut a = ClientCore::open(vault(dir.path().join("a.vault"))).unwrap();
    let mut b = ClientCore::open(vault(dir.path().join("b.vault"))).unwrap();
    let mut sa = SavedServer {
        address: format!("127.0.0.1:{}", _running.address.port()),
        ..SavedServer::default()
    };
    let mut sb = sa.clone();
    a.connect(&mut sa, Some(Zeroizing::new("voice join password".into())))
        .unwrap();
    std::thread::sleep(Duration::from_millis(10050));
    b.connect(&mut sb, Some(Zeroizing::new("voice join password".into())))
        .unwrap();
    a.poll(&mut sa).unwrap();
    b.poll(&mut sb).unwrap();
    let channel = sa
        .channels
        .iter()
        .find(|c| c.kind == "voice")
        .unwrap()
        .clone();
    let account = a.account(&sa);
    let av = Arc::new(Mutex::new(VoiceSession::new(
        Arc::new(Mutex::new(a)),
        sa,
        channel.clone(),
        "build/models".into(),
    )));
    let b_core = Arc::new(Mutex::new(b));
    let bv = Arc::new(Mutex::new(VoiceSession::new(
        b_core.clone(),
        sb,
        channel.clone(),
        "build/models".into(),
    )));
    assert!(av
        .lock()
        .unwrap()
        .request("/encode", json!({"data":""}))
        .is_err());
    assert!(av.lock().unwrap().request("/unknown", json!({})).is_err());
    assert!(av.lock().unwrap().request("/signal", json!([])).is_err());
    assert!(av
        .lock()
        .unwrap()
        .request("/codec", json!({"op":"init"}))
        .unwrap()["ready"]
        .as_bool()
        .unwrap());
    assert_eq!(
        av.lock().unwrap().request("/settings", json!({})).unwrap()["account"],
        account
    );
    let mut ad = VoiceDriver::new(av.clone(), Synthetic::default()).unwrap();
    let mut bd = VoiceDriver::new(bv.clone(), Synthetic::default()).unwrap();
    ad.step(false).unwrap();
    bd.step(false).unwrap();
    ad.audio.block_after_read = true;
    ad.step(true).unwrap();
    assert!(!ad.transmitting);
    assert_eq!(
        bv.lock()
            .unwrap()
            .request("/signal", json!({"action":"heartbeat"}))
            .unwrap()["frames"],
        json!([])
    );
    ad.audio.block_after_read = false;
    ad.audio.blocked = false;
    ad.step(true).unwrap();
    assert!(ad.transmitting && ad.audio.recording);
    bd.step(false).unwrap();
    assert!(bd.audio.played > 0);
    bd.step(true).unwrap();
    assert!(!bd.transmitting && !bd.audio.recording);
    ad.step(false).unwrap();
    bd.step(true).unwrap();
    ad.step(false).unwrap();
    assert!(ad.audio.played > 0);
    bd.step(false).unwrap();
    av.lock()
        .unwrap()
        .request("/signal", json!({"action":"claim"}))
        .unwrap();
    let pcm = float_bytes(&vec![0.1; 3200]);
    let e = av
        .lock()
        .unwrap()
        .request("/encode", json!({"data":crypto::b64(&pcm)}))
        .unwrap();
    av.lock()
        .unwrap()
        .request("/media", json!({"envelope":e}))
        .unwrap();
    bv.lock()
        .unwrap()
        .request("/signal", json!({"action":"heartbeat"}))
        .unwrap();
    bv.lock()
        .unwrap()
        .request("/decode", json!({"author":account,"envelope":e}))
        .unwrap();
    assert!(bv
        .lock()
        .unwrap()
        .request("/decode", json!({"author":account,"envelope":e}))
        .is_err());
    let stream = av
        .lock()
        .unwrap()
        .request("/video/start", json!({"source":"screen","kbps":500}))
        .unwrap()["stream"]
        .as_str()
        .unwrap()
        .to_owned();
    let video = av
        .lock()
        .unwrap()
        .request(
            "/video/encode",
            json!({"stream":stream,"data":crypto::b64(b"synthetic video")}),
        )
        .unwrap();
    av.lock()
        .unwrap()
        .request("/video/media", json!({"stream":stream,"envelope":video}))
        .unwrap();
    let poll = bv
        .lock()
        .unwrap()
        .request("/video/poll", json!({"receive":[stream]}))
        .unwrap();
    assert_eq!(poll["frames"].as_array().unwrap().len(), 1);
    let decoded = bv
        .lock()
        .unwrap()
        .request(
            "/video/decode",
            json!({"stream":stream,"author":account,"envelope":video}),
        )
        .unwrap();
    assert_eq!(
        crypto::unb64(decoded["data"].as_str().unwrap()).unwrap(),
        b"synthetic video"
    );
    assert!(bv
        .lock()
        .unwrap()
        .request(
            "/video/decode",
            json!({"stream":stream,"author":account,"envelope":video})
        )
        .is_err());
    assert!(av
        .lock()
        .unwrap()
        .request("/video/unknown", json!({}))
        .is_err());
    assert!(av
        .lock()
        .unwrap()
        .request(
            "/video/encode",
            json!({"stream":stream,"data":crypto::b64(&vec![0;125001])})
        )
        .is_err());
    av.lock()
        .unwrap()
        .request("/video/stop", json!({"stream":stream}))
        .unwrap();
    let mut bridge = VoiceBridge::start(av.clone()).unwrap();
    let uri = url::Url::parse(&bridge.uri(false)).unwrap();
    let query = format!("token={}", uri.fragment().unwrap());
    let token = url::form_urlencoded::parse(query.as_bytes())
        .next()
        .unwrap()
        .1
        .into_owned();
    for path in [
        "/",
        "/video.html",
        "/video.html?reopen=1",
        "/video.js",
        "/fonts/IBMPlexMono-Regular.ttf",
    ] {
        assert_eq!(
            bridge_request(bridge.address, path, None, None, None).status,
            200
        );
    }
    assert_eq!(
        bridge_request(bridge.address, "/settings", Some(&token), None, None).status,
        200
    );
    assert_eq!(
        bridge_request(bridge.address, "/settings", Some("bad"), None, None).status,
        400
    );
    assert_eq!(
        bridge_request(
            bridge.address,
            "/settings",
            Some(&token),
            Some("https://attacker.invalid"),
            None
        )
        .status,
        400
    );
    assert_eq!(
        bridge_request(bridge.address, "/", None, None, Some("localhost")).status,
        400
    );
    assert_eq!(
        bridge_request(bridge.address, "/missing", None, None, None).status,
        400
    );
    bridge.stop();
    assert!(av.lock().unwrap().request("/settings", json!({})).is_err());
    assert!(ad.step(true).is_err());
    assert!(!ad.audio.recording);
    bd.stop();
    ad.stop();
    #[cfg(feature = "audio")]
    {
        // Exercise the real native worker's fail-closed device path on every host.
        let mut core = b_core.lock().unwrap();
        core.state.microphone = "__VCH_test_missing_microphone__".into();
        drop(core);
        let saved = bv.lock().unwrap().server.clone();
        let native_session = Arc::new(Mutex::new(VoiceSession::new(
            b_core,
            saved,
            channel,
            "build/models".into(),
        )));
        let mut native = vch_core::voice::NativeVoice::start(native_session.clone());
        native.press(true);
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        loop {
            let status = native.status.lock().unwrap().clone();
            if status == "Microphone unavailable" {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "Native worker status: {status}"
            );
            std::thread::sleep(Duration::from_millis(20));
        }
        native.stop();
        assert!(native_session
            .lock()
            .unwrap()
            .request("/settings", json!({}))
            .is_err());
    }
}
