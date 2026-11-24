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

    fn state_backup_path(&self) -> PathBuf {
        self.data_dir.join("state.backup.json")
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

    /// Loads user state across any version update without ever silently resetting to dummy data.
    /// Falls back through: full parse -> section-by-section JSON migration -> `state.backup.json`.
    pub fn load_state(&self) -> AppState {
        let path = self.state_path();
        if let Ok(raw) = fs::read_to_string(&path) {
            if let Ok(st) = serde_json::from_str::<AppState>(&raw) {
                return sanitize_state(st);
            }
            // Never discard existing user data on schema evolution: preserve raw copy & migrate section-by-section
            let _ = fs::write(self.data_dir.join("state.pre-migration.json"), &raw);
            if let Some(migrated) = recover_state_from_json(&raw) {
                let sanitized = sanitize_state(migrated);
                let _ = self.save_state(&sanitized);
                return sanitized;
            }
        }
        // Fallback to rolling backup if primary state.json was interrupted mid-write
        if let Ok(raw) = fs::read_to_string(self.state_backup_path()) {
            if let Ok(st) = serde_json::from_str::<AppState>(&raw) {
                return sanitize_state(st);
            }
            if let Some(migrated) = recover_state_from_json(&raw) {
                return sanitize_state(migrated);
            }
        }
        AppState::default()
    }

    pub fn save_state(&self, state: &AppState) -> std::io::Result<()> {
        let primary = self.state_path();
        if primary.exists() {
            let _ = fs::copy(&primary, self.state_backup_path());
        }
        write_atomic(&primary, &serde_json::to_vec_pretty(state)?)
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

fn sanitize_state(mut st: AppState) -> AppState {
    if st.workspace.boards.is_empty() {
        st.workspace = crate::models::Workspace::default();
    } else if !st
        .workspace
        .boards
        .iter()
        .any(|b| b.id == st.workspace.active_board_id)
    {
        st.workspace.active_board_id = st.workspace.boards[0].id.clone();
    }
    if st.workout_types.is_empty() {
        st.workout_types = crate::models::default_workout_types();
    }
    st
}

/// Section-by-section resilient recovery so a schema change in one field never wipes user data.
pub fn recover_state_from_json(raw: &str) -> Option<AppState> {
    let val: serde_json::Value = serde_json::from_str(raw).ok()?;
    let obj = val.as_object()?;
    let mut st = AppState::default();
    let mut recovered_any = false;

    if let Some(v) = obj.get("workspace") {
        if let Ok(ws) = serde_json::from_value(v.clone()) {
            st.workspace = ws;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("settings") {
        if let Ok(s) = serde_json::from_value(v.clone()) {
            st.settings = s;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("sessions") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.sessions = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("activeSessionId") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.active_session_id = x;
        }
    }
    if let Some(v) = obj.get("voiceChat") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.voice_chat = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("notes") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.notes = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("transactions") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.transactions = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("budgets") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.budgets = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("workoutTypes") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.workout_types = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("workouts") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.workouts = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("focusSessions") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.focus_sessions = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("calendarReminders") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.calendar_reminders = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("savedWorkoutTracks") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.saved_workout_tracks = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("downloadedWorkoutTracks") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.downloaded_workout_tracks = x;
            recovered_any = true;
        }
    }
    if let Some(v) = obj.get("supportTickets") {
        if let Ok(x) = serde_json::from_value(v.clone()) {
            st.support_tickets = x;
            recovered_any = true;
        }
    }

    if recovered_any {
        Some(st)
    } else {
        None
    }
}

fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes)?;
    fs::rename(&tmp, path)?;
    Ok(())
}
