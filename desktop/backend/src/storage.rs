use crate::models::{AppState, PasswordEntry};
use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use pbkdf2::pbkdf2_hmac;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PairedPhone {
    pub device_name: String,
    pub app_version: String,
    pub token: String,
    pub paired_at: i64,
    pub last_synced_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct EncryptedVaultFile {
    salt_b64: String,
    nonce_b64: String,
    ciphertext_b64: String,
}

pub struct Storage {
    pub data_dir: PathBuf,
    pub music_cache_dir: PathBuf,
    pub music_downloads_dir: PathBuf,
}

impl Storage {
    pub fn new(custom_dir: Option<PathBuf>) -> Self {
        let data_dir = custom_dir.unwrap_or_else(|| {
            dirs::data_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join("Voxban")
        });
        let music_cache_dir = data_dir.join("music_cache");
        let music_downloads_dir = dirs::audio_dir()
            .or_else(dirs::download_dir)
            .unwrap_or_else(|| data_dir.clone())
            .join("Voxban");

        let _ = fs::create_dir_all(&data_dir);
        let _ = fs::create_dir_all(&music_cache_dir);
        let _ = fs::create_dir_all(&music_downloads_dir);

        Self {
            data_dir,
            music_cache_dir,
            music_downloads_dir,
        }
    }

    fn state_path(&self) -> PathBuf {
        self.data_dir.join("state.json")
    }

    fn keys_path(&self) -> PathBuf {
        self.data_dir.join("keys.json")
    }

    fn pairing_path(&self) -> PathBuf {
        self.data_dir.join("pairing.json")
    }

    fn vault_path(&self) -> PathBuf {
        self.data_dir.join("vault.enc")
    }

    pub fn load_state(&self) -> AppState {
        let path = self.state_path();
        if let Ok(raw) = fs::read_to_string(&path) {
            if let Ok(st) = serde_json::from_str::<AppState>(&raw) {
                return st;
            }
        }
        AppState::default()
    }

    pub fn save_state(&self, state: &AppState) -> std::io::Result<()> {
        write_atomic(&self.state_path(), &serde_json::to_vec_pretty(state)?)
    }

    pub fn load_keys(&self) -> HashMap<String, String> {
        fs::read_to_string(self.keys_path())
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
            .unwrap_or_default()
    }

    pub fn save_keys(&self, keys: &HashMap<String, String>) -> std::io::Result<()> {
        write_atomic(&self.keys_path(), &serde_json::to_vec_pretty(keys)?)
    }

    pub fn load_paired_phone(&self) -> Option<PairedPhone> {
        fs::read_to_string(self.pairing_path())
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
    }

    pub fn save_paired_phone(&self, phone: Option<&PairedPhone>) -> std::io::Result<()> {
        let p = self.pairing_path();
        match phone {
            Some(ph) => write_atomic(&p, &serde_json::to_vec_pretty(ph)?),
            None => {
                let _ = fs::remove_file(&p);
                Ok(())
            }
        }
    }

    pub fn vault_exists(&self) -> bool {
        self.vault_path().exists()
    }

    pub fn unlock_vault(&self, passphrase: &str) -> Result<Vec<PasswordEntry>, String> {
        let path = self.vault_path();
        if !path.exists() {
            return Ok(Vec::new());
        }
        let raw = fs::read_to_string(&path).map_err(|e| format!("Failed reading vault: {e}"))?;
        let enc: EncryptedVaultFile =
            serde_json::from_str(&raw).map_err(|e| format!("Corrupt vault file: {e}"))?;
        let salt = B64
            .decode(&enc.salt_b64)
            .map_err(|_| "Corrupt vault salt".to_string())?;
        let nonce_bytes = B64
            .decode(&enc.nonce_b64)
            .map_err(|_| "Corrupt vault nonce".to_string())?;
        let ciphertext = B64
            .decode(&enc.ciphertext_b64)
            .map_err(|_| "Corrupt vault ciphertext".to_string())?;

        let key = derive_key(passphrase, &salt);
        let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| "Cipher error".to_string())?;
        let nonce = Nonce::from_slice(&nonce_bytes);
        let plaintext = cipher
            .decrypt(nonce, ciphertext.as_ref())
            .map_err(|_| "Wrong vault master password.".to_string())?;
        serde_json::from_slice(&plaintext).map_err(|e| format!("Vault JSON error: {e}"))
    }

    pub fn save_vault(&self, passphrase: &str, entries: &[PasswordEntry]) -> Result<(), String> {
        let mut salt = [0u8; 16];
        let mut nonce_bytes = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut salt);
        rand::thread_rng().fill_bytes(&mut nonce_bytes);

        let key = derive_key(passphrase, &salt);
        let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| "Cipher init error".to_string())?;
        let plaintext = serde_json::to_vec(entries).map_err(|e| e.to_string())?;
        let ciphertext = cipher
            .encrypt(Nonce::from_slice(&nonce_bytes), plaintext.as_ref())
            .map_err(|_| "Encryption failed".to_string())?;

        let file = EncryptedVaultFile {
            salt_b64: B64.encode(salt),
            nonce_b64: B64.encode(nonce_bytes),
            ciphertext_b64: B64.encode(ciphertext),
        };
        let bytes = serde_json::to_vec_pretty(&file).map_err(|e| e.to_string())?;
        write_atomic(&self.vault_path(), &bytes).map_err(|e| e.to_string())
    }
}

fn derive_key(passphrase: &str, salt: &[u8]) -> [u8; 32] {
    let mut key = [0u8; 32];
    pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), salt, 120_000, &mut key);
    key
}

fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes)?;
    fs::rename(&tmp, path)?;
    Ok(())
}
