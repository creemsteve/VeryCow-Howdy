use serde_json::json;
use std::sync::{Arc, Mutex};
use vch_core::{
    crypto,
    mobile::MobileClient,
    models::Role,
    server::{ChatServer, RunningServer},
    vault::Vault,
};
use zeroize::Zeroizing;

#[test]
fn mobile_vault_reopen_validation_and_display_boundary() {
    let directory = tempfile::tempdir().unwrap();
    let open = || {
        MobileClient::open(
            directory.path().into(),
            Zeroizing::new("mobile vault password".into()),
            "build/models".into(),
        )
    };
    let mut mobile = open().unwrap();
    let initial = mobile.request("/state", json!({})).unwrap();
    assert!(initial.get("privateKey").is_none());
    assert!(initial.get("publicKey").is_none());
    assert!(mobile
        .request(
            "/profile",
            json!({"name":"","bio":"","pronouns":"","avatar":""})
        )
        .is_err());
    mobile
        .request(
            "/profile",
            json!({"name":"Flip member","bio":"Keypad client","pronouns":"they/them","avatar":""}),
        )
        .unwrap();
    assert!(mobile
        .request(
            "/audio",
            json!({"inputGain":301,"outputGain":100,"outgoingBitRate":6000})
        )
        .is_err());
    mobile.request("/audio",json!({"inputGain":125,"outputGain":80,"outgoingBitRate":3000,"pttKey":"138","microphone":"Default","speakers":"Default","darkMode":false})).unwrap();
    assert!(mobile.request("/unknown", json!({})).is_err());
    assert!(mobile.request("/state", json!([])).is_err());
    drop(mobile);
    assert!(MobileClient::open(
        directory.path().into(),
        Zeroizing::new("wrong vault password".into()),
        "build/models".into()
    )
    .is_err());
    let mut mobile = open().unwrap();
    let state = mobile.request("/state", json!({})).unwrap();
    assert_eq!(state["name"], "Flip member");
    assert_eq!(state["inputGain"], 125);
    assert_eq!(state["darkMode"], false);
}

#[test]
fn mobile_current_core_https_chat_images_roles_admin_and_reconnect() {
    let directory = tempfile::tempdir().unwrap();
    let mut server = ChatServer::open(
        Vault::open(
            directory.path().join("server.vault"),
            Zeroizing::new("server vault password".into()),
        )
        .unwrap(),
    )
    .unwrap();
    server
        .set_password(Zeroizing::new("mobile join password".into()))
        .unwrap();
    server.state.hash_bits = 8;
    let server = Arc::new(Mutex::new(server));
    let running = RunningServer::start(server.clone(), "127.0.0.1", 0).unwrap();
    let mut mobile = MobileClient::open(
        directory.path().join("mobile"),
        Zeroizing::new("mobile vault password".into()),
        "build/models".into(),
    )
    .unwrap();
    let joined=mobile.request("/join",json!({"address":format!("127.0.0.1:{}",running.address.port()),"password":"mobile join password"})).unwrap();
    let id = joined["server"].as_str().unwrap();
    let state = mobile.request("/state", json!({})).unwrap();
    let saved = &state["servers"][0];
    for secret in ["passwordKey", "channelKeys", "previousChannelKeys"] {
        assert!(saved.get(secret).is_none());
    }
    assert_eq!(saved["connected"], true);
    let account = saved["account"].as_str().unwrap();
    server.lock().unwrap().state.admins.insert(account.into());
    mobile.request("/poll", json!({"server":id})).unwrap();
    let state = mobile.request("/state", json!({})).unwrap();
    let saved = &state["servers"][0];
    let channel = saved["channels"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["type"] == "text")
        .unwrap()["id"]
        .as_str()
        .unwrap();
    mobile.request("/send",json!({"server":id,"channel":channel,"kind":"text","data":crypto::b64(b"Hello from the current Rust mobile core")})).unwrap();
    let state = mobile.request("/state", json!({})).unwrap();
    let message = &state["servers"][0]["messages"][0];
    let plain = mobile
        .request("/decrypt", json!({"server":id,"message":message["id"]}))
        .unwrap();
    assert_eq!(
        crypto::unb64(plain["data"].as_str().unwrap()).unwrap(),
        b"Hello from the current Rust mobile core"
    );
    let prepared = mobile
        .request(
            "/image",
            json!({"data":crypto::b64(include_bytes!("../assets/vch.png")),"profile":false}),
        )
        .unwrap();
    mobile.request("/send",json!({"server":id,"channel":channel,"kind":"images","data":crypto::b64(&serde_json::to_vec(&json!([prepared["data"]])).unwrap())})).unwrap();
    assert!(mobile
        .request(
            "/request",
            json!({"server":id,"route":"challenge","data":{}})
        )
        .is_err());
    let role = Role {
        name: "Mobile role".into(),
        ..Role::default()
    };
    mobile.request("/request",json!({"server":id,"route":"roles","data":{"roles":[role],"everyonePermissions":["view","send","connect"]}})).unwrap();
    mobile.request("/request",json!({"server":id,"route":"admin","data":{"name":"Mobile tested server","description":"Native Rust mobile adapter","image":"","channels":saved["adminChannels"]}})).unwrap();
    mobile.request("/poll", json!({"server":id})).unwrap();
    let state = mobile.request("/state", json!({})).unwrap();
    assert_eq!(state["servers"][0]["name"], "Mobile tested server");
    assert_eq!(state["servers"][0]["roles"][0]["name"], "Mobile role");
    mobile.request("/disconnect", json!({"server":id})).unwrap();
    assert_eq!(
        mobile.request("/state", json!({})).unwrap()["servers"][0]["connected"],
        false
    );
    // Tor settings invalidate the session. A missing SOCKS service cannot fall back to direct HTTPS.
    mobile
        .request(
            "/connection",
            json!({"server":id,"torOnly":true,"torSocksPort":1}),
        )
        .unwrap();
    assert!(mobile.request("/connect", json!({"server":id})).is_err());
    mobile
        .request(
            "/connection",
            json!({"server":id,"torOnly":false,"torSocksPort":9050}),
        )
        .unwrap();
    std::thread::sleep(std::time::Duration::from_millis(10050));
    mobile.request("/connect", json!({"server":id})).unwrap();
    mobile.request("/remove", json!({"server":id})).unwrap();
    assert!(mobile.request("/state", json!({})).unwrap()["servers"]
        .as_array()
        .unwrap()
        .is_empty());
}

#[cfg(feature = "neural")]
#[test]
fn mobile_friends_calls_dac_voice_video_and_active_session_removal() {
    let directory = tempfile::tempdir().unwrap();
    let mut server = ChatServer::open(
        Vault::open(
            directory.path().join("server.vault"),
            Zeroizing::new("media vault password".into()),
        )
        .unwrap(),
    )
    .unwrap();
    server
        .set_password(Zeroizing::new("media join password".into()))
        .unwrap();
    server.state.hash_bits = 8;
    let server = Arc::new(Mutex::new(server));
    let running = RunningServer::start(server, "127.0.0.1", 0).unwrap();
    let open = |name: &str| {
        MobileClient::open(
            directory.path().join(name),
            Zeroizing::new("mobile vault password".into()),
            "build/models".into(),
        )
        .unwrap()
    };
    let mut a = open("alice");
    let mut b = open("bob");
    let join = json!({"address":format!("127.0.0.1:{}",running.address.port()),"password":"media join password"});
    let id = a.request("/join", join.clone()).unwrap()["server"]
        .as_str()
        .unwrap()
        .to_string();
    std::thread::sleep(std::time::Duration::from_millis(10050));
    b.request("/join", join).unwrap();
    a.request("/poll", json!({"server":id})).unwrap();
    let state_a = a.request("/state", json!({})).unwrap();
    let state_b = b.request("/state", json!({})).unwrap();
    let alice = state_a["servers"][0]["account"].as_str().unwrap();
    let bob = state_b["servers"][0]["account"].as_str().unwrap();
    a.request(
        "/request",
        json!({"server":id,"route":"friend","data":{"action":"request","account":bob}}),
    )
    .unwrap();
    b.request("/poll", json!({"server":id})).unwrap();
    assert_eq!(
        b.request("/state", json!({})).unwrap()["servers"][0]["friendRequests"][0],
        alice
    );
    b.request(
        "/request",
        json!({"server":id,"route":"friend","data":{"action":"accept","account":alice}}),
    )
    .unwrap();
    a.request("/poll", json!({"server":id})).unwrap();
    b.request("/poll", json!({"server":id})).unwrap();
    let state = a.request("/state", json!({})).unwrap();
    let channel = state["servers"][0]["friendships"][0]["voiceChannel"]
        .as_str()
        .unwrap()
        .to_string();
    a.request(
        "/request",
        json!({"server":id,"route":"call","data":{"action":"ring","account":bob}}),
    )
    .unwrap();
    b.request("/poll", json!({"server":id})).unwrap();
    let call = b.request("/state", json!({})).unwrap()["servers"][0]["calls"][0]["id"]
        .as_str()
        .unwrap()
        .to_string();
    b.request(
        "/request",
        json!({"server":id,"route":"call","data":{"action":"accept","id":call}}),
    )
    .unwrap();
    for client in [&mut a, &mut b] {
        client
            .request("/voice/open", json!({"server":id,"channel":channel}))
            .unwrap();
    }
    a.request("/media/signal", json!({"action":"claim"}))
        .unwrap();
    let pcm = vch_core::codec::float_bytes(
        &(0..3200)
            .map(|i| (i as f32 * 0.1728).sin() * 0.2)
            .collect::<Vec<_>>(),
    );
    let envelope = a
        .request("/media/encode", json!({"data":crypto::b64(&pcm)}))
        .unwrap();
    a.request("/media/media", json!({"envelope":envelope}))
        .unwrap();
    let signal = b
        .request("/media/signal", json!({"action":"heartbeat"}))
        .unwrap();
    assert_eq!(signal["frames"].as_array().unwrap().len(), 1);
    let decoded = b
        .request(
            "/media/decode",
            json!({"author":alice,"envelope":signal["frames"][0]}),
        )
        .unwrap();
    assert_eq!(
        crypto::unb64(decoded["data"].as_str().unwrap())
            .unwrap()
            .len(),
        pcm.len()
    );
    assert!(b
        .request(
            "/media/decode",
            json!({"author":alice,"envelope":signal["frames"][0]})
        )
        .is_err());
    a.request("/media/signal", json!({"action":"release"}))
        .unwrap();
    let stream = a
        .request("/media/video/start", json!({"source":"camera","kbps":500}))
        .unwrap()["stream"]
        .as_str()
        .unwrap()
        .to_string();
    let payload = b"synthetic encoded-video transport payload";
    let video = a
        .request(
            "/media/video/encode",
            json!({"stream":stream,"data":crypto::b64(payload)}),
        )
        .unwrap();
    a.request(
        "/media/video/media",
        json!({"stream":stream,"envelope":video}),
    )
    .unwrap();
    assert!(b
        .request("/media/video/poll", json!({"after":-1,"receive":[]}))
        .unwrap()["frames"]
        .as_array()
        .unwrap()
        .is_empty());
    let poll = b
        .request("/media/video/poll", json!({"after":-1,"receive":[stream]}))
        .unwrap();
    assert_eq!(poll["frames"].as_array().unwrap().len(), 1);
    let decoded = b
        .request(
            "/media/video/decode",
            json!({"stream":stream,"author":alice,"envelope":poll["frames"][0]["envelope"]}),
        )
        .unwrap();
    assert_eq!(
        crypto::unb64(decoded["data"].as_str().unwrap()).unwrap(),
        payload
    );
    a.request(
        "/media/video/configure",
        json!({"stream":stream,"kbps":250}),
    )
    .unwrap();
    a.request("/media/video/stop", json!({"stream":stream}))
        .unwrap();
    a.request("/remove", json!({"server":id})).unwrap();
    b.request("/voice/close", json!({})).unwrap();
}
