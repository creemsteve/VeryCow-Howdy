use serde_json::json;
use vch_core::{address, codec::*, crypto, images, limiter::JoinAttemptLimiter, models::*};

#[test]
fn addresses() {
    assert_eq!(
        vch_core::server::canonical_ip("::ffff:127.0.0.1").unwrap(),
        "127.0.0.1"
    );
    assert_eq!(
        vch_core::server::canonical_ip("0:0:0:0:0:0:0:1").unwrap(),
        "::1"
    );
    assert!(vch_core::server::canonical_ip("not an IP").is_err());
    for (input, expected) in [
        ("chat.example.com", "https://chat.example.com:585"),
        ("192.168.1.10", "https://192.168.1.10:585"),
        ("::1", "https://[::1]:585"),
        ("[2001:db8::1]", "https://[2001:db8::1]:585"),
        (
            " HTTPS://CHAT.EXAMPLE.COM/ ",
            "https://chat.example.com:585",
        ),
        ("localhost:12345", "https://localhost:12345"),
        ("https://localhost:443", "https://localhost:443"),
    ] {
        assert_eq!(address::normalize(input).unwrap(), expected);
    }
    for bad in [
        "",
        "http://example.com",
        "https://user:pass@example.com",
        "example.com/path",
        "example.com?x=1",
        "example.com#fragment",
        "example.com:70000",
        "example.com:0",
        "host with spaces",
        "https://example.com\\evil",
    ] {
        assert!(address::normalize(bad).is_err(), "{bad}");
    }
    assert_eq!(
        address::migrate_legacy("localhost:8443").unwrap(),
        "https://localhost:585"
    );
    assert_eq!(
        address::migrate_legacy("localhost:6000").unwrap(),
        "https://localhost:6000"
    );
}

#[test]
fn cooldown() {
    let mut l = JoinAttemptLimiter::default();
    assert_eq!(l.acquire("one", 1000), 0);
    assert_eq!(l.acquire("one", 1000), 10);
    assert_eq!(l.acquire("two", 1000), 0);
    assert_eq!(l.acquire("one", 10999), 1);
    assert_eq!(l.acquire("one", 11000), 0);
    l.continued("one", 20000);
    assert_eq!(l.acquire("one", 21000), 9);
    for n in 0..16384 {
        l.acquire(&n.to_string(), 21000);
    }
    assert_eq!(l.acquire("full", 21000), 10);
    assert_eq!(l.acquire("cleared", 40000), 0);
}

#[test]
fn cryptography() {
    assert_eq!(
        crypto::hex(&crypto::hash(b"abc")),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
    assert_eq!(
        crypto::unb64(&crypto::b64(b"\0hello\xff")).unwrap(),
        b"\0hello\xff"
    );
    assert!(crypto::unb64("%%").is_err());
    let key = crypto::random(32);
    assert_ne!(key, crypto::random(32));
    let encrypted = crypto::seal(&key, b"secret", b"aad").unwrap();
    assert_eq!(crypto::open(&key, &encrypted, b"aad").unwrap(), b"secret");
    assert_ne!(encrypted, crypto::seal(&key, b"secret", b"aad").unwrap());
    assert!(crypto::open(&key, &encrypted, b"wrong").is_err());
    assert!(crypto::open(&key, b"short", b"").is_err());
    assert!(crypto::seal(&[1], b"", b"").is_err());
    assert!(crypto::open(&[1], &encrypted, b"aad").is_err());
    let mac = crypto::hmac(&key, b"hello");
    assert!(crypto::verify_hmac(&key, b"hello", &mac));
    assert!(!crypto::verify_hmac(&key, b"changed", &mac));
    let c = crypto::compress(&vec![42; 10000]).unwrap();
    assert_eq!(crypto::decompress(&c, 10000).unwrap(), vec![42; 10000]);
    assert!(crypto::decompress(&c, 9999).is_err());
    assert!(crypto::decompress(b"bad", 10).is_err());
    let (public, private) = crypto::identity().unwrap();
    let signature = crypto::sign(&private, b"message").unwrap();
    assert!(crypto::verify(&public, b"message", &signature).unwrap());
    assert!(!crypto::verify(&public, b"changed", &signature).unwrap());
    assert!(!crypto::verify(&public, b"message", &crypto::b64(&[0; 3309])).unwrap());
    assert_ne!(
        crypto::account("one", &public),
        crypto::account("two", &public)
    );
    let answer = crypto::solve("challenge", 8).unwrap();
    assert!(crypto::proof("challenge", &answer, 8));
    assert!(!crypto::proof("challenge", &"0".repeat(25), 8));
    assert!(!crypto::proof("challenge", "0", 7));
    assert!(crypto::solve("challenge", 23).is_err());
}

#[test]
fn model_contracts() {
    assert!(vch_core::data_dir("client").ends_with(".vch/client"));
    assert!(vch_core::models_dir().ends_with("models"));
    let mut e = Envelope {
        server: "s".into(),
        channel: "c".into(),
        author: "a".into(),
        id: "i".into(),
        kind: "text".into(),
        cipher: "x".into(),
        time: 123,
        ..Envelope::default()
    };
    assert_eq!(e.aad(), b"VCH/payload/1|s|c|a|i|text");
    assert_eq!(e.signed_bytes(), b"VCH/message/1|s|c|a|i|123|text|x");
    e.key_revision = 2;
    assert_eq!(e.aad(), b"VCH/payload/2|s|c|a|i|text|2");
    assert_eq!(e.signed_bytes(), b"VCH/message/2|s|c|a|i|123|text|x|2");
    let mut keys = ChannelKeySet::default();
    let old = keys.current.clone();
    keys.rotate();
    assert_eq!(keys.previous, vec![old]);
    assert_eq!(keys.revision, 2);
    let mut c = Channel::default();
    assert!(c.permits("a"));
    c.allow.insert("b".into());
    assert!(!c.permits("a"));
    assert!(c.permits("b"));
    assert!(Friendship {
        first: "a".into(),
        second: "b".into(),
        ..Friendship::default()
    }
    .includes("b"));
    assert!(vch_core::now() > 1_000_000_000_000);
    let _: ClientState = serde_json::from_value(json!({})).unwrap();
}

#[test]
fn permission_precedence() {
    let mut s = ServerState::default();
    let mut c = Channel::default();
    assert!(s.permits(&c, "a", "send"));
    s.everyone_permissions.remove("send");
    assert!(!s.permits(&c, "a", "send"));
    let mut low = Role::default();
    low.members.insert("a".into());
    low.permissions.insert("send".into());
    let mut high = Role::default();
    high.members.insert("a".into());
    high.permissions.insert("manage_roles".into());
    s.roles = vec![low.clone(), high.clone()];
    assert!(s.permits(&c, "a", "send"));
    c.role_overrides
        .insert("everyone".into(), [("send".into(), false)].into());
    assert!(!s.permits(&c, "a", "send"));
    c.role_overrides
        .insert(low.id, [("send".into(), false)].into());
    c.role_overrides
        .insert(high.id, [("send".into(), true)].into());
    assert!(s.permits(&c, "a", "send"));
    assert_eq!(s.highest_role("a"), Some(1));
    assert!(s.global_permissions("a").contains("manage_roles"));
    assert!(s.can_manage_roles("a"));
    assert!(!s.can_manage_roles("b"));
    s.admins.insert("a".into());
    c.allow.insert("b".into());
    assert!(!s.permits(&c, "a", "view"));
    assert!(!s.permits(&c, "a", "connect"));
}

#[test]
fn packets() {
    for count in 1..100 {
        let values: Vec<u16> = (0..count).map(|i| (i * 71 % 1024) as u16).collect();
        let bytes = pack10(&values).unwrap();
        assert_eq!(unpack10(&bytes, count).unwrap(), values);
    }
    assert!(pack10(&[1024]).is_err());
    assert!(unpack10(&[], 1).is_err());
    let p = Packet {
        books: 3,
        frames: 7,
        samples: 1600,
        codes: (0..21).collect(),
    };
    let bytes = p.encode().unwrap();
    let p2 = Packet::decode(&bytes, 12).unwrap();
    assert_eq!(p2.codes, p.codes);
    assert_eq!(p2.samples, 1600);
    for n in 0..bytes.len() {
        assert!(Packet::decode(&bytes[..n], 12).is_err());
    }
    assert!(Packet::decode(&bytes, 2).is_err());
    assert!(Packet {
        books: 0,
        frames: 1,
        samples: 1,
        codes: vec![]
    }
    .encode()
    .is_err());
    assert_eq!(
        pcm_floats(&float_bytes(&[-2., 0., 2.])).unwrap(),
        vec![-1., 0., 1.]
    );
    assert!(pcm_floats(&f32::NAN.to_le_bytes()).is_err());
    assert!(pcm_floats(&[0]).is_err());
}

#[test]
fn image_preparation() {
    use image::{DynamicImage, ImageFormat};
    let mut source = std::io::Cursor::new(Vec::new());
    DynamicImage::new_rgb8(128, 64)
        .write_to(&mut source, ImageFormat::Png)
        .unwrap();
    let bytes = images::prepare_bytes(source.get_ref(), 10000, 64).unwrap();
    assert!(images::is_webp(&bytes));
    let decoded = images::decode(&bytes, 64).unwrap();
    assert_eq!(decoded.width(), 64);
    assert_eq!(decoded.height(), 32);
    assert!(images::decode(&bytes, 63).is_err());
    assert!(images::decode(b"not an image", 100).is_err());
    assert!(images::prepare_bytes(source.get_ref(), 1, 64).is_err());
    assert!(images::prepare_bytes(b"invalid", 10000, 64).is_err());
    assert!(images::collection(&[]).is_err());
    assert!(images::collection(&vec![bytes.clone(); 4]).is_err());
    assert!(images::collection(&[b"bad".to_vec()]).is_err());
    assert_eq!(
        serde_json::from_slice::<Vec<String>>(
            &images::collection(std::slice::from_ref(&bytes)).unwrap()
        )
        .unwrap(),
        vec![crypto::b64(&bytes)]
    );
    let temp = tempfile::tempdir().unwrap();
    let file = temp.path().join("image.png");
    std::fs::write(&file, source.get_ref()).unwrap();
    assert_eq!(images::prepare(&file, 10000, 64).unwrap(), bytes);
}

#[cfg(feature = "audio")]
#[test]
fn audio_conversion() {
    use vch_core::audio;
    let floats = audio::to_float(&[0, 128, 0, 0, 255, 127], 100).unwrap();
    assert_eq!(floats[0], -1.);
    assert_eq!(floats[1], 0.);
    assert!(audio::to_float(&[0], 100).is_err());
    assert_eq!(
        audio::to_pcm(&[f32::NAN, 0., 1., -1.], 100),
        vec![0, 0, 0, 0, 255, 127, 1, 128]
    );
    assert_eq!(
        audio::resample(&[0., 1., 0., -1.], 4, 2).unwrap(),
        vec![0., 0.]
    );
    assert_eq!(audio::resample(&[], 4, 2).unwrap(), Vec::<f32>::new());
    assert!(audio::resample(&[1.], 0, 2).is_err());
}

#[cfg(feature = "audio")]
#[test]
fn unavailable_audio_devices_fail_without_capture() {
    let _ = vch_core::audio::devices();
    assert!(vch_core::audio::Devices::open(
        "__VCH_test_missing_microphone__",
        "__VCH_test_missing_speakers__"
    )
    .is_err());
}
