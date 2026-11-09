use crate::crypto;
use anyhow::{bail, Context, Result};
use fs2::FileExt;
use serde_json::{json, Value};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;
const MAGIC: &[u8] = b"VCHVAULT1";
pub struct Vault {
    path: PathBuf,
    salt: Vec<u8>,
    key: Zeroizing<[u8; 32]>,
    pub data: Value,
    _lock: File,
}
impl Vault {
    pub fn open(path: impl AsRef<Path>, password: Zeroizing<String>) -> Result<Self> {
        let path = path.as_ref().to_path_buf();
        let parent = path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        fs::create_dir_all(parent)?;
        let lock = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(path.with_file_name(format!(
                    "{}.lock",
                    path.file_name()
                        .context("Invalid vault path")?
                        .to_string_lossy()
                )))?;
        lock.try_lock_exclusive()
            .context("This vault is already open in another application")?;
        let (salt, key, data) = if path.exists() {
            let b = fs::read(&path)?;
            if b.len() < MAGIC.len() + 16 + 28 || !b.starts_with(MAGIC) {
                bail!("Unsupported vault");
            }
            let salt = b[MAGIC.len()..MAGIC.len() + 16].to_vec();
            let key = crypto::derive(&password, &salt)?;
            let plain = Zeroizing::new(
                crypto::open(key.as_ref(), &b[MAGIC.len() + 16..], MAGIC)
                    .context("Wrong password or damaged vault")?,
            );
            let data: Value = serde_json::from_slice(&plain)?;
            if !data.is_object() {
                bail!("Invalid vault data");
            }
            (salt, key, data)
        } else {
            if password.chars().count() < 12 {
                bail!("Use at least 12 characters for a new vault");
            }
            let salt = crypto::random(16);
            let key = crypto::derive(&password, &salt)?;
            (salt, key, json!({}))
        };
        let vault = Self {
            path,
            salt,
            key,
            data,
            _lock: lock,
        };
        if !vault.path.exists() {
            vault.save()?;
        }
        Ok(vault)
    }
    pub fn save(&self) -> Result<()> {
        let plain = Zeroizing::new(serde_json::to_vec(&self.data)?);
        let cipher = crypto::seal(self.key.as_ref(), &plain, MAGIC)?;
        let parent = self
            .path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        let mut temp = tempfile::NamedTempFile::new_in(parent)?;
        temp.write_all(MAGIC)?;
        temp.write_all(&self.salt)?;
        temp.write_all(&cipher)?;
        temp.as_file().sync_all()?;
        temp.persist(&self.path).map_err(|e| e.error)?;
        Ok(())
    }
}
