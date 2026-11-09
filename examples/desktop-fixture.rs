//! Native desktop verification against isolated test vaults. Never opens user data.
use anyhow::{ensure, Result};
use std::{net::TcpListener, path::PathBuf};
use vch_core::{
    client::ClientCore,
    desktop::{self, App},
    models::SavedServer,
    server::ChatServer,
    vault::Vault,
};
use zeroize::Zeroizing;

fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    ensure!(
        args.len() == 3,
        "desktop-fixture server|client FIXTURE_DIRECTORY"
    );
    let root = PathBuf::from(&args[2]).canonicalize()?;
    ensure!(
        root.join("DESKTOP-TEST-ONLY").is_file(),
        "Test marker required"
    );
    let data = root.join(&args[1]);
    let vault = Vault::open(
        data.join("data.vault"),
        Zeroizing::new("desktop fixture password".into()),
    )?;
    let models = PathBuf::from("build/models").canonicalize()?;
    let app = match args[1].as_str() {
        "server" => {
            let mut server = ChatServer::open(vault)?;
            server.state.name = "Desktop verification".into();
            server.state.hash_bits = 8;
            if server.state.password_verifier.is_none() {
                server.set_password(Zeroizing::new("desktop join password".into()))?;
            }
            let listener = TcpListener::bind("127.0.0.1:0")?;
            server.state.port = listener.local_addr()?.port();
            drop(listener);
            server.save()?;
            std::fs::write(
                root.join("address"),
                format!("127.0.0.1:{}", server.state.port),
            )?;
            App::from_server(server, data, models)
        }
        "client" => {
            let mut client = ClientCore::open(vault)?;
            client.state.name = "Desktop verifier".into();
            client.save()?;
            let mut saved = client
                .state
                .servers
                .first()
                .cloned()
                .unwrap_or(SavedServer {
                    address: std::fs::read_to_string(root.join("address"))?,
                    ..SavedServer::default()
                });
            client.connect(
                &mut saved,
                Some(Zeroizing::new("desktop join password".into())),
            )?;
            App::from_client(client, data, models)
        }
        _ => anyhow::bail!("Unknown fixture role"),
    };
    desktop::run_app(app)
}
