use anyhow::{Context, Result};
use clap::{Parser, ValueEnum};
use std::{
    io::{BufRead, Write},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
};
use vch_core::{
    server::{ChatServer, RunningServer},
    vault::Vault,
};
use zeroize::Zeroizing;
#[derive(Clone, Copy, Debug, ValueEnum, PartialEq)]
enum Role {
    Client,
    Server,
    Daemon,
    Codec,
}
#[derive(Parser)]
#[command(
    version,
    about = "Private communities with encrypted archives, chat and media"
)]
struct Args {
    #[arg(value_enum, default_value = "client")]
    role: Role,
    #[arg(
        long,
        help = "Vault directory (defaults to ~/.vch/client or ~/.vch/server)"
    )]
    data: Option<PathBuf>,
    #[arg(long)]
    models: Option<PathBuf>,
    #[arg(long, default_value = "0.0.0.0")]
    bind: String,
    #[arg(long)]
    port: Option<u16>,
    #[arg(long, help = "Prompt to change the server join password")]
    set_join_password: bool,
    #[arg(long, help = "Grant administrator access to a registered account hash")]
    admin: Option<String>,
}
fn main() {
    if let Err(e) = run(Args::parse()) {
        eprintln!("VCH: {e:#}");
        std::process::exit(1);
    }
}
fn run(args: Args) -> Result<()> {
    let models = args.models.unwrap_or_else(vch_core::models_dir);
    if args.role == Role::Codec {
        let mut codec = vch_core::codec::DacCodec::open(&models)?;
        for line in std::io::stdin().lock().lines() {
            let line = line?;
            let result = (|| -> Result<serde_json::Value> {
                anyhow::ensure!(line.len() <= 100000, "Codec request too large");
                codec.request(&serde_json::from_str(&line)?)
            })();
            let out = match result {
                Ok(v) => v,
                Err(e) => serde_json::json!({"error":e.to_string()}),
            };
            println!("{}", out);
            std::io::stdout().flush()?;
        }
        return Ok(());
    }
    let data = args.data.unwrap_or_else(|| {
        vch_core::data_dir(if args.role == Role::Client {
            "client"
        } else {
            "server"
        })
    });
    if args.role != Role::Daemon {
        #[cfg(feature = "desktop")]
        return vch_core::desktop::run(
            if args.role == Role::Client {
                vch_core::desktop::Mode::Client
            } else {
                vch_core::desktop::Mode::Server
            },
            data,
            models,
        );
        #[cfg(not(feature = "desktop"))]
        anyhow::bail!("Build with desktop support to open the client or server GUI; use daemon for a headless server");
    }
    let password = Zeroizing::new(rpassword::prompt_password(
        "Server vault password (unlocks server data on this host): ",
    )?);
    let mut server = ChatServer::open(Vault::open(data.join("data.vault"), password)?)?;
    if args.set_join_password || server.state.password_verifier.is_none() {
        let password = Zeroizing::new(rpassword::prompt_password(
            "New join password (grants members access to this server): ",
        )?);
        let confirm = Zeroizing::new(rpassword::prompt_password("Confirm join password: ")?);
        anyhow::ensure!(*password == *confirm, "Passwords do not match");
        server.set_password(password)?;
    }
    if let Some(port) = args.port {
        anyhow::ensure!(port > 0, "Port must be between 1 and 65535");
        server.state.port = port;
    }
    if let Some(admin) = args.admin {
        anyhow::ensure!(
            server.state.accounts.contains_key(&admin),
            "Administrator must be a registered account"
        );
        server.state.admins.insert(admin);
    }
    server.save()?;
    let port = server.state.port;
    let server = Arc::new(Mutex::new(server));
    let mut running =
        RunningServer::start(server, &args.bind, port).context("Cannot start server")?;
    println!("VCH listening on https://{}", running.address);
    let stopped = Arc::new(AtomicBool::new(false));
    let signal = stopped.clone();
    ctrlc::set_handler(move || signal.store(true, Ordering::Relaxed))?;
    while !stopped.load(Ordering::Relaxed) {
        std::thread::park_timeout(std::time::Duration::from_millis(100));
    }
    running.stop();
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn command_line() {
        let a = Args::try_parse_from([
            "vch",
            "daemon",
            "--bind",
            "127.0.0.1",
            "--port",
            "585",
            "--data",
            "test",
            "--set-join-password",
        ])
        .unwrap();
        assert_eq!(a.role, Role::Daemon);
        assert_eq!(a.port, Some(585));
        assert!(a.set_join_password);
        assert_eq!(Args::try_parse_from(["vch"]).unwrap().role, Role::Client);
        assert!(Args::try_parse_from(["vch", "unknown"]).is_err());
    }
}
