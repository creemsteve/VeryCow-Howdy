use serde_json::Value;
use std::{
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread,
    time::Duration,
};
use vch_core::{
    client::{ApiException, ClientCore},
    crypto, http,
    models::*,
    server::{ChatServer, RunningServer},
    tls::PinVerifier,
    vault::Vault,
};
use zeroize::Zeroizing;
fn vault(path: impl AsRef<std::path::Path>) -> Vault {
    Vault::open(path, Zeroizing::new("network vault password".into())).unwrap()
}
fn server(dir: &std::path::Path) -> (Arc<Mutex<ChatServer>>, RunningServer) {
    let mut server = ChatServer::open(vault(dir.join("server.vault"))).unwrap();
    server
        .set_password(Zeroizing::new("network join password".into()))
        .unwrap();
    server.state.hash_bits = 8;
    let server = Arc::new(Mutex::new(server));
    let running = RunningServer::start(server.clone(), "127.0.0.1", 0).unwrap();
    (server, running)
}
struct Proxy {
    port: u16,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
    hosts: Arc<Mutex<Vec<String>>>,
}
impl Proxy {
    fn new(destination: u16) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        listener.set_nonblocking(true).unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let end = stop.clone();
        let hosts = Arc::new(Mutex::new(Vec::new()));
        let recorded = hosts.clone();
        let handle = thread::spawn(move || {
            while !end.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut client, _)) => {
                        let recorded = recorded.clone();
                        thread::spawn(move || {
                            client.set_nonblocking(false).unwrap();
                            client
                                .set_read_timeout(Some(Duration::from_secs(10)))
                                .unwrap();
                            let mut greeting = [0; 3];
                            client.read_exact(&mut greeting).unwrap();
                            assert_eq!(greeting, [5, 1, 0]);
                            client.write_all(&[5, 0]).unwrap();
                            let mut head = [0; 4];
                            client.read_exact(&mut head).unwrap();
                            assert_eq!(head, [5, 1, 0, 3]);
                            let mut len = [0];
                            client.read_exact(&mut len).unwrap();
                            let mut name = vec![0; len[0] as usize];
                            client.read_exact(&mut name).unwrap();
                            recorded
                                .lock()
                                .unwrap()
                                .push(String::from_utf8(name).unwrap());
                            let mut port = [0; 2];
                            client.read_exact(&mut port).unwrap();
                            assert_eq!(u16::from_be_bytes(port), destination);
                            let mut server =
                                TcpStream::connect(("127.0.0.1", destination)).unwrap();
                            client.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).unwrap();
                            let mut reverse_client = client.try_clone().unwrap();
                            let mut reverse_server = server.try_clone().unwrap();
                            let reverse = thread::spawn(move || {
                                let _ = std::io::copy(&mut reverse_server, &mut reverse_client);
                                let _ = reverse_client.shutdown(std::net::Shutdown::Write);
                            });
                            let _ = std::io::copy(&mut client, &mut server);
                            let _ = server.shutdown(std::net::Shutdown::Write);
                            let _ = reverse.join();
                        });
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5))
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            port,
            stop,
            handle: Some(handle),
            hosts,
        }
    }
}
impl Drop for Proxy {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(t) = self.handle.take() {
            t.join().unwrap();
        }
    }
}

#[test]
fn real_https_tor_chat_rotation_and_archives() {
    let dir = tempfile::tempdir_in("target").unwrap();
    let (core, mut running) = server(dir.path());
    let address = format!("127.0.0.1:{}", running.address.port());
    let mut alice = ClientCore::open(vault(dir.path().join("alice.vault"))).unwrap();
    let mut saved = SavedServer {
        address: address.clone(),
        ..SavedServer::default()
    };
    alice
        .connect(
            &mut saved,
            Some(Zeroizing::new("network join password".into())),
        )
        .unwrap();
    let text = saved
        .channels
        .iter()
        .find(|c| c.kind == "text")
        .unwrap()
        .clone();
    let own = alice.account(&saved);
    assert!(alice.connected(&saved));
    assert_eq!(
        saved.fingerprint.as_deref(),
        Some(core.lock().unwrap().fingerprint())
    );
    alice
        .send(&mut saved, &text, "text", b"from Alice")
        .unwrap();
    assert_eq!(
        alice
            .decrypt(&saved, saved.messages.last().unwrap())
            .unwrap(),
        b"from Alice"
    );
    let archived = saved.messages[0].clone();
    assert!(alice.send(&mut saved, &text, "text", b"").is_err());
    assert!(alice.send(&mut saved, &text, "image", b"not webp").is_err());
    assert!(alice.send(&mut saved, &text, "images", b"[]").is_err());
    assert!(alice
        .send(
            &mut saved,
            &Channel {
                kind: "voice".into(),
                ..Channel::default()
            },
            "text",
            b"bad"
        )
        .is_err());
    core.lock().unwrap().rotate_channel_key(&text.id).unwrap();
    alice
        .send(&mut saved, &text, "text", b"key refreshed automatically")
        .unwrap();
    assert_eq!(saved.key_revisions[&text.id], 2);
    assert_eq!(alice.decrypt(&saved, &archived).unwrap(), b"from Alice");
    assert_eq!(
        alice
            .decrypt(&saved, saved.messages.last().unwrap())
            .unwrap(),
        b"key refreshed automatically"
    );
    let pin = PinVerifier::new(Some(&"f".repeat(64))).unwrap();
    assert!(http::post(&address, "challenge", None, &pin, b"{}", None).is_err());
    assert!(PinVerifier::new(Some("bad")).is_err());
    let proxy = Proxy::new(running.address.port());
    thread::sleep(Duration::from_millis(10050));
    let mut bob = ClientCore::open(vault(dir.path().join("bob.vault"))).unwrap();
    let mut remote = SavedServer {
        address: format!("unresolvable.invalid:{}", running.address.port()),
        tor_only: true,
        tor_socks_port: proxy.port,
        ..SavedServer::default()
    };
    bob.connect(
        &mut remote,
        Some(Zeroizing::new("network join password".into())),
    )
    .unwrap();
    assert!(proxy
        .hosts
        .lock()
        .unwrap()
        .iter()
        .all(|host| host == "unresolvable.invalid"));
    assert!(remote.messages.len() >= 2);
    bob.send(
        &mut remote,
        &text,
        "text",
        b"through SOCKS without destination DNS",
    )
    .unwrap();
    alice.poll(&mut saved).unwrap();
    assert_eq!(
        alice
            .decrypt(&saved, saved.messages.last().unwrap())
            .unwrap(),
        b"through SOCKS without destination DNS"
    );
    assert_eq!(bob.decrypt(&remote, &archived).unwrap(), b"from Alice");
    let mut invalid = archived.clone();
    invalid.cipher = "tampered".into();
    assert!(alice.decrypt(&saved, &invalid).is_err());
    let mut forged = saved.clone();
    forged.accounts.get_mut(&own).unwrap().public_key = crypto::identity().unwrap().0;
    assert!(alice.decrypt(&forged, &archived).is_err());
    let pin = saved.fingerprint.clone();
    let id = saved.id.clone();
    alice.store(&saved).unwrap();
    alice.disconnect(&saved);
    assert!(!alice.connected(&saved));
    drop(alice);
    let alice = ClientCore::open(vault(dir.path().join("alice.vault"))).unwrap();
    assert_eq!(alice.state.servers[0].id, id);
    assert_eq!(alice.state.servers[0].fingerprint, pin);
    assert_eq!(
        alice.decrypt(&alice.state.servers[0], &archived).unwrap(),
        b"from Alice"
    );
    let sink = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let dead_port = sink.local_addr().unwrap().port();
    drop(sink);
    bob.connection_settings(&mut remote, true, dead_port)
        .unwrap();
    assert!(!bob.connected(&remote));
    assert!(bob.connect(&mut remote, None).is_err());
    assert_eq!(remote.tor_socks_port, dead_port);
    running.stop();
    assert!(RunningServer::start(core.clone(), "127.0.0.1", 0).is_ok());
}

#[test]
fn http_admission_headers_and_invalid_requests() {
    let dir = tempfile::tempdir_in("target").unwrap();
    let (core, _running) = server(dir.path());
    let address = format!("127.0.0.1:{}", _running.address.port());
    let pin = PinVerifier::new(None).unwrap();
    let reply = http::post(&address, "challenge", None, &pin, b"not JSON", None).unwrap();
    assert_eq!(reply.status, 400);
    let reply = http::post(&address, "challenge", None, &pin, b"{}", None).unwrap();
    assert_eq!(reply.status, 429);
    let v: Value = serde_json::from_slice(&reply.body).unwrap();
    assert_eq!(
        reply.headers["retry-after"].parse::<u64>().unwrap(),
        v["retryAfterSeconds"].as_u64().unwrap()
    );
    assert_eq!(reply.headers["cache-control"], "no-store");
    assert_eq!(reply.headers["x-content-type-options"], "nosniff");
    assert_eq!(
        pin.fingerprint().unwrap(),
        core.lock().unwrap().fingerprint()
    );
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS12])
    .unwrap()
    .dangerous()
    .with_custom_certificate_verifier(pin.clone())
    .with_no_client_auth();
    let socket = TcpStream::connect(_running.address).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(10)))
        .unwrap();
    let connection = rustls::ClientConnection::new(
        Arc::new(config),
        rustls::pki_types::ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();
    let mut stream = rustls::StreamOwned::new(connection, socket);
    stream
        .write_all(b"POST /api/poll HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n{}")
        .unwrap();
    stream.flush().unwrap();
    assert_eq!(
        http::read_reply(&mut std::io::BufReader::new(stream))
            .unwrap()
            .status,
        401
    );
    assert!(http::post(&address, "bad\r\npath", None, &pin, b"{}", None).is_err());
    assert!(http::post(&address, "poll", None, &pin, b"{}", Some("bad\r\ntoken")).is_err());
    let mut c = ClientCore::open(vault(dir.path().join("wrong.vault"))).unwrap();
    let mut saved = SavedServer {
        address,
        ..SavedServer::default()
    };
    let e = c
        .connect(&mut saved, Some(Zeroizing::new("wrong password".into())))
        .unwrap_err();
    assert_eq!(e.downcast_ref::<ApiException>().unwrap().status, 429);
    assert!(saved.fingerprint.is_none());
}
