pub mod address;
#[cfg(feature = "audio")]
pub mod audio;
pub mod auth;
pub mod client;
pub mod codec;
pub mod crypto;
#[cfg(feature = "desktop")]
pub mod desktop;
pub mod http;
pub mod images;
pub mod limiter;
pub mod models;
pub mod server;
pub mod tls;
pub mod vault;
pub mod video;
pub mod voice;

pub fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub fn data_dir(role: &str) -> std::path::PathBuf {
    std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| ".".into())
        .join(".vch")
        .join(role)
}
pub fn models_dir() -> std::path::PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.join("models")))
        .filter(|p| p.join("dac.json").exists())
        .unwrap_or_else(|| "build/models".into())
}
