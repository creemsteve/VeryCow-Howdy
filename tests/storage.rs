use serde_json::{json, Value};
use vch_core::{client::ClientCore, crypto, models::SavedServer, server::ChatServer, vault::Vault};
use zeroize::Zeroizing;
fn password() -> Zeroizing<String> {
    Zeroizing::new("storage test password".into())
}
#[test]
fn vault_roundtrip_lock_password_tamper() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.vault");
    assert!(Vault::open(&path, Zeroizing::new("short".into())).is_err());
    {
        let mut vault = Vault::open(&path, password()).unwrap();
        vault.data["secret"] = json!("confidential plaintext");
        vault.save().unwrap();
        assert!(Vault::open(&path, password()).is_err());
        assert!(!std::fs::read(&path)
            .unwrap()
            .windows(22)
            .any(|w| w == b"confidential plaintext"));
    }
    assert!(Vault::open(&path, Zeroizing::new("wrong password".into())).is_err());
    {
        let vault = Vault::open(&path, password()).unwrap();
        assert_eq!(vault.data["secret"], "confidential plaintext");
    }
    let mut bytes = std::fs::read(&path).unwrap();
    *bytes.last_mut().unwrap() ^= 1;
    std::fs::write(&path, &bytes).unwrap();
    assert!(Vault::open(&path, password()).is_err());
    std::fs::write(&path, b"invalid vault").unwrap();
    assert!(Vault::open(&path, password()).is_err());
}

#[test]
fn identity_and_server_persist() {
    let dir = tempfile::tempdir().unwrap();
    let client_path = dir.path().join("client.vault");
    let mut c = ClientCore::open(Vault::open(&client_path, password()).unwrap()).unwrap();
    let public = c.state.public_key.clone();
    let mut saved = SavedServer {
        id: "s".into(),
        address: "localhost".into(),
        ..SavedServer::default()
    };
    assert!(!c.connected(&saved));
    c.connection_settings(&mut saved, true, 9999).unwrap();
    assert!(saved.tor_only);
    assert!(c.connection_settings(&mut saved, true, 0).is_err());
    c.disconnect(&saved);
    c.save().unwrap();
    drop(c);
    let c = ClientCore::open(Vault::open(&client_path, password()).unwrap()).unwrap();
    assert_eq!(c.state.public_key, public);
    assert!(c.state.servers[0].tor_only);
    drop(c);
    let path = dir.path().join("server.vault");
    let mut server = ChatServer::open(Vault::open(&path, password()).unwrap()).unwrap();
    let id = server.state.id.clone();
    let fingerprint = server.fingerprint().to_owned();
    assert_eq!(server.state.channels.len(), 2);
    assert!(server.set_password(Zeroizing::new("short".into())).is_err());
    server.set_password(password()).unwrap();
    let channel = server.state.channels[0].id.clone();
    let key = server.state.channel_keys[&channel].current.clone();
    server.rotate_channel_key(&channel).unwrap();
    assert_ne!(server.state.channel_keys[&channel].current, key);
    assert!(server.rotate_channel_key("missing").is_err());
    server.ban(&"a".repeat(64), false).unwrap();
    server.ban("127.0.0.9", true).unwrap();
    server.ban("0:0:0:0:0:0:0:1", true).unwrap();
    assert!(server.state.banned_ips.contains("::1"));
    server.state.banned_ips.insert("0:0:0:0:0:0:0:2".into());
    server.save().unwrap();
    assert!(server.ban("invalid", true).is_err());
    assert!(server.ban("invalid", false).is_err());
    server.reset_sessions();
    drop(server);
    let server = ChatServer::open(Vault::open(&path, password()).unwrap()).unwrap();
    assert_eq!(server.state.id, id);
    assert_eq!(server.fingerprint(), fingerprint);
    assert!(server.state.banned_ips.contains("127.0.0.9"));
    assert!(server.state.banned_ips.contains("::2"));
}

#[test]
fn java_fixtures() {
    let path = std::path::Path::new("tests/fixtures/java.json");
    assert!(
        path.exists(),
        "Generate Java compatibility fixtures before running tests"
    );
    let f: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    let bytes = |key: &str| crypto::unb64(f[key].as_str().unwrap()).unwrap();
    assert_eq!(
        crypto::password_key("compatibility password", &bytes("salt")).as_ref(),
        bytes("passwordKey")
    );
    assert_eq!(
        crypto::derive("compatibility password", &bytes("salt"))
            .unwrap()
            .as_ref(),
        bytes("argon")
    );
    let compressed = crypto::open(&bytes("aesKey"), &bytes("sealed"), b"fixture").unwrap();
    assert_eq!(compressed, bytes("compressed"));
    assert_eq!(
        crypto::decompress(&compressed, 1000).unwrap(),
        bytes("plain")
    );
    assert!(crypto::verify(
        f["publicKey"].as_str().unwrap(),
        &bytes("plain"),
        f["signature"].as_str().unwrap()
    )
    .unwrap());
    let signature = crypto::sign(f["privateKey"].as_str().unwrap(), &bytes("plain")).unwrap();
    assert!(crypto::verify(
        f["publicKey"].as_str().unwrap(),
        &bytes("plain"),
        &signature
    )
    .unwrap());
    assert_eq!(
        vch_core::tls::fingerprint(f["tlsStore"].as_str().unwrap()).unwrap(),
        f["fingerprint"]
    );
    vch_core::tls::server(f["tlsStore"].as_str().unwrap()).unwrap();
}

#[test]
fn generated_tls_store_loads_for_server() {
    let store = vch_core::tls::create().unwrap();
    vch_core::tls::server(&store).unwrap();
}

#[test]
fn legacy_tls_key_rejects_mismatched_certificate() {
    let f: Value = serde_json::from_str(include_str!("fixtures/java.json")).unwrap();
    let legacy = p12_keystore::KeyStore::from_pkcs12(
        &crypto::unb64(f["tlsStore"].as_str().unwrap()).unwrap(),
        "",
    )
    .unwrap();
    let (_, legacy_chain) = legacy.private_key_chain().unwrap();
    let other = vch_core::tls::create().unwrap();
    let other = p12_keystore::KeyStore::from_pkcs12(&crypto::unb64(&other).unwrap(), "").unwrap();
    let (_, other_chain) = other.private_key_chain().unwrap();
    let chain = p12_keystore::PrivateKeyChain::new(
        legacy_chain.key(),
        b"vch",
        other_chain.chain().to_vec(),
    );
    let mut store = p12_keystore::KeyStore::new();
    store.add_entry("vch", p12_keystore::KeyStoreEntry::PrivateKeyChain(chain));
    let encoded = crypto::b64(&store.writer("").write().unwrap());
    assert!(vch_core::tls::server(&encoded).is_err());
}

#[test]
fn java_vault_and_legacy_archive_migration() {
    let f: Value = serde_json::from_str(include_str!("fixtures/java.json")).unwrap();
    let dir = tempfile::tempdir_in("target").unwrap();
    let pass = || Zeroizing::new("compatibility password".into());
    let vault_path = dir.path().join("java.vault");
    std::fs::write(
        &vault_path,
        crypto::unb64(f["vault"].as_str().unwrap()).unwrap(),
    )
    .unwrap();
    let vault = Vault::open(&vault_path, pass()).unwrap();
    assert_eq!(vault.data["fixture"], "saved by Java");
    drop(vault);
    let client_path = dir.path().join("client.vault");
    std::fs::write(
        &client_path,
        crypto::unb64(f["clientVault"].as_str().unwrap()).unwrap(),
    )
    .unwrap();
    let client = ClientCore::open(Vault::open(&client_path, pass()).unwrap()).unwrap();
    let saved = &client.state.servers[0];
    assert_eq!(saved.address, "https://localhost:585");
    assert_eq!(saved.join_protocol, 2);
    assert_eq!(saved.messages[0].key_revision, 0);
    assert_eq!(
        client.decrypt(saved, &saved.messages[0]).unwrap(),
        b"legacy archive retained"
    );
    let public = client.state.public_key.clone();
    let pin = saved.fingerprint.clone();
    drop(client);
    let client = ClientCore::open(Vault::open(&client_path, pass()).unwrap()).unwrap();
    assert_eq!(client.state.public_key, public);
    assert_eq!(client.state.servers[0].fingerprint, pin);
    let server_path = dir.path().join("server.vault");
    std::fs::write(
        &server_path,
        crypto::unb64(f["serverVault"].as_str().unwrap()).unwrap(),
    )
    .unwrap();
    let server = ChatServer::open(Vault::open(&server_path, pass()).unwrap()).unwrap();
    assert_eq!(server.state.port, 585);
    assert_eq!(server.state.join_protocol, 2);
    assert_eq!(server.state.id, client.state.servers[0].id);
    assert_eq!(server.fingerprint(), pin.as_deref().unwrap());
}
