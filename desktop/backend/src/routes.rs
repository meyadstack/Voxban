use crate::ai;
use crate::biometrics;
use crate::models::{
    now_ms, AppState, PasswordEntry, SupportTicket, WorkoutTrack, APP_VERSION, GITHUB_RELEASES_URL,
};
use crate::music::MusicEngine;
use crate::storage::{PairedPhone, Storage};
use crate::support;
use crate::sync::{generate_pairing_session, merge_states, PairingSessionInfo};
use crate::updater;
use axum::{
    body::Body,
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        DefaultBodyLimit, Path as AxumPath, Query, State,
    },
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
    Json, Router,
};
use rand::Rng;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncSeekExt, SeekFrom};
use tokio::sync::{broadcast, RwLock};
use tower_http::cors::{Any, CorsLayer};

#[cfg(feature = "embed-ui")]
#[derive(rust_embed::RustEmbed)]
#[folder = "../frontend/dist"]
struct EmbeddedFrontend;

pub struct AppContext {
    pub port: u16,
    pub storage: Storage,
    pub state: RwLock<AppState>,
    pub keys: RwLock<HashMap<String, String>>,
    pub paired_phone: RwLock<Option<PairedPhone>>,
    pub pairing_session: RwLock<PairingSessionInfo>,
    pub music: Arc<MusicEngine>,
    pub events: broadcast::Sender<String>,
    pub frontend_dir: Option<PathBuf>,
    pub unlocked_vault_passphrase: RwLock<Option<String>>,
}

impl AppContext {
    pub fn new(port: u16, data_dir: Option<PathBuf>, frontend_dir: Option<PathBuf>) -> Arc<Self> {
        let storage = Storage::new(data_dir);
        let initial_state = storage.load_state();
        let initial_keys = storage.load_keys();
        let initial_phone = storage.load_paired_phone();
        let session = generate_pairing_session(port);
        let music = MusicEngine::new(
            storage.music_cache_dir.clone(),
            storage.music_downloads_dir.clone(),
        );
        let (tx, _) = broadcast::channel(64);
        Arc::new(Self {
            port,
            storage,
            state: RwLock::new(initial_state),
            keys: RwLock::new(initial_keys),
            paired_phone: RwLock::new(initial_phone),
            pairing_session: RwLock::new(session),
            music,
            events: tx,
            frontend_dir,
            unlocked_vault_passphrase: RwLock::new(None),
        })
    }

    pub fn notify(&self, event: &str, payload: Value) {
        let msg = json!({ "event": event, "data": payload }).to_string();
        let _ = self.events.send(msg);
    }
}

pub fn build_router(ctx: Arc<AppContext>) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    Router::new()
        .route("/api/health", get(health))
        .route("/api/state", get(get_state).put(put_state))
        .route("/api/keys", get(get_keys).put(put_keys))
        .route("/api/pair/info", get(pair_info))
        .route("/api/pair/refresh", post(pair_refresh))
        .route("/api/pair/confirm", post(pair_confirm))
        .route("/api/pair/code", post(pair_by_code))
        .route("/api/pair/unlink", post(pair_unlink))
        .route("/api/sync", post(sync_from_phone))
        .route("/api/ai/chat", post(ai_chat))
        .route("/api/music/search", get(music_search))
        .route("/api/music/radio", get(music_radio))
        .route("/api/music/stream/{video_id}", get(music_stream))
        .route("/api/music/download", post(music_download))
        .route("/api/music/download-file/{video_id}", get(music_download_file))
        .route("/api/music/download/{video_id}", delete(music_delete_download))
        .route("/api/music/open-folder", post(music_open_folder))
        .route("/api/vault/status", get(vault_status))
        .route("/api/vault/unlock", post(vault_unlock))
        .route("/api/vault/lock", post(vault_lock))
        .route("/api/vault/save", post(vault_save))
        .route("/api/vault/generate", post(vault_generate))
        .route("/api/vault/biometric/unlock", post(vault_biometric_unlock))
        .route("/api/vault/biometric/enable", post(vault_biometric_enable))
        .route("/api/vault/biometric/disable", post(vault_biometric_disable))
        .route("/api/support/format", post(support_format))
        .route("/api/updates/check", get(updates_check))
        .route("/api/ws", get(ws_handler))
        .fallback(serve_frontend)
        .layer(DefaultBodyLimit::max(32 * 1024 * 1024))
        .layer(cors)
        .with_state(ctx)
}

// ---------------------------------------------------------------- Health & State

async fn health(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    let session = ctx.pairing_session.read().await;
    Json(json!({
        "ok": true,
        "version": APP_VERSION,
        "pcName": session.pc_name,
        "os": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
        "downloadsDir": ctx.music.downloads_dir().to_string_lossy(),
    }))
}

async fn get_state(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    let st = ctx.state.read().await.clone();
    Json(st)
}

async fn put_state(
    State(ctx): State<Arc<AppContext>>,
    Json(incoming): Json<AppState>,
) -> impl IntoResponse {
    {
        let mut st = ctx.state.write().await;
        *st = incoming.clone();
        let _ = ctx.storage.save_state(&st);
    }
    ctx.notify("state_updated", json!({ "at": now_ms() }));
    Json(incoming)
}

async fn get_keys(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    let keys = ctx.keys.read().await;
    let masked: HashMap<String, bool> = keys
        .iter()
        .map(|(k, v)| (k.clone(), !v.trim().is_empty()))
        .collect();
    Json(json!({ "configured": masked }))
}

async fn put_keys(
    State(ctx): State<Arc<AppContext>>,
    Json(updates): Json<HashMap<String, String>>,
) -> impl IntoResponse {
    let mut keys = ctx.keys.write().await;
    for (k, v) in updates {
        let trimmed = v.trim().to_string();
        if trimmed.is_empty() {
            keys.remove(&k);
        } else {
            keys.insert(k, trimmed);
        }
    }
    let _ = ctx.storage.save_keys(&keys);
    let masked: HashMap<String, bool> = keys
        .iter()
        .map(|(k, v)| (k.clone(), !v.trim().is_empty()))
        .collect();
    Json(json!({ "ok": true, "configured": masked }))
}

// ---------------------------------------------------------------- Phone QR Pairing & Sync

async fn pair_info(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    let phone = ctx.paired_phone.read().await.clone();
    let session = ctx.pairing_session.read().await.clone();
    Json(json!({
        "paired": phone.is_some(),
        "pairedPhone": phone,
        "session": session,
        "githubReleasesUrl": GITHUB_RELEASES_URL,
    }))
}

async fn pair_refresh(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    let next = generate_pairing_session(ctx.port);
    *ctx.pairing_session.write().await = next.clone();
    Json(json!({ "ok": true, "session": next }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairConfirmPayload {
    #[serde(default)]
    token: String,
    #[serde(default)]
    code: String,
    #[serde(default)]
    device_name: String,
    #[serde(default)]
    app_version: String,
    state: Option<AppState>,
}

async fn pair_confirm(
    State(ctx): State<Arc<AppContext>>,
    Json(req): Json<PairConfirmPayload>,
) -> impl IntoResponse {
    let session = ctx.pairing_session.read().await.clone();
    let existing_token = ctx.paired_phone.read().await.as_ref().map(|p| p.token.clone());

    let valid = (!req.token.is_empty() && req.token == session.token)
        || (!req.token.is_empty() && existing_token.as_deref() == Some(req.token.as_str()));
    if !valid {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({ "ok": false, "message": "Invalid or expired QR token. Refresh QR on PC and try again." })),
        );
    }

    complete_pairing(ctx, session, req).await
}

async fn pair_by_code(
    State(ctx): State<Arc<AppContext>>,
    Json(req): Json<PairConfirmPayload>,
) -> impl IntoResponse {
    let session = ctx.pairing_session.read().await.clone();
    if req.code.trim().is_empty() || req.code.trim() != session.code {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({ "ok": false, "message": "Pairing code does not match the 6-digit code shown on PC." })),
        );
    }
    complete_pairing(ctx, session, req).await
}

async fn complete_pairing(
    ctx: Arc<AppContext>,
    session: PairingSessionInfo,
    req: PairConfirmPayload,
) -> (StatusCode, Json<Value>) {
    let now = now_ms();
    let merged = {
        let mut local = ctx.state.write().await;
        if let Some(remote) = req.state.as_ref() {
            *local = merge_states(&local, remote);
            let _ = ctx.storage.save_state(&local);
        }
        local.clone()
    };

    let phone = PairedPhone {
        device_name: if req.device_name.trim().is_empty() {
            "Voxban Android".into()
        } else {
            req.device_name.trim().to_string()
        },
        app_version: req.app_version,
        token: session.token.clone(),
        paired_at: now,
        last_synced_at: now,
    };
    {
        *ctx.paired_phone.write().await = Some(phone.clone());
        let _ = ctx.storage.save_paired_phone(Some(&phone));
    }

    ctx.notify("paired", json!({ "phone": phone }));
    ctx.notify("state_updated", json!({ "at": now }));

    (
        StatusCode::OK,
        Json(json!({
            "ok": true,
            "pcName": session.pc_name,
            "token": session.token,
            "message": format!("Paired with {}", session.pc_name),
            "state": merged,
        })),
    )
}

async fn pair_unlink(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    *ctx.paired_phone.write().await = None;
    let _ = ctx.storage.save_paired_phone(None);
    let fresh = generate_pairing_session(ctx.port);
    *ctx.pairing_session.write().await = fresh.clone();
    ctx.notify("unlinked", json!({}));
    Json(json!({ "ok": true, "session": fresh }))
}

async fn sync_from_phone(
    State(ctx): State<Arc<AppContext>>,
    headers: HeaderMap,
    Json(remote): Json<AppState>,
) -> impl IntoResponse {
    let provided = headers
        .get("X-Voxban-Token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let session_tok = ctx.pairing_session.read().await.token.clone();
    let paired_tok = ctx.paired_phone.read().await.as_ref().map(|p| p.token.clone());

    let authed = !provided.is_empty()
        && (provided == session_tok || paired_tok.as_deref() == Some(provided));
    if !authed {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({ "error": "Unauthorized sync token" })),
        );
    }

    let now = now_ms();
    let merged = {
        let mut local = ctx.state.write().await;
        *local = merge_states(&local, &remote);
        let _ = ctx.storage.save_state(&local);
        local.clone()
    };
    if let Some(ph) = ctx.paired_phone.write().await.as_mut() {
        ph.last_synced_at = now;
        let _ = ctx.storage.save_paired_phone(Some(ph));
    }
    ctx.notify("state_updated", json!({ "at": now }));

    (StatusCode::OK, Json(serde_json::to_value(merged).unwrap_or(json!({}))))
}

// ---------------------------------------------------------------- AI Chat

#[derive(Deserialize)]
struct AiChatReq {
    prompt: String,
}

async fn ai_chat(
    State(ctx): State<Arc<AppContext>>,
    Json(req): Json<AiChatReq>,
) -> impl IntoResponse {
    let keys = ctx.keys.read().await.clone();
    let mut state_clone = ctx.state.read().await.clone();
    let reply = ai::handle_assistant_message(&req.prompt, &mut state_clone, &keys).await;

    if !reply.changes.is_empty() {
        let mut st = ctx.state.write().await;
        *st = state_clone.clone();
        let _ = ctx.storage.save_state(&st);
        ctx.notify("state_updated", json!({ "at": now_ms() }));
    }

    Json(json!({
        "reply": reply.reply,
        "changes": reply.changes,
        "engine": reply.engine,
        "state": state_clone,
    }))
}

// ---------------------------------------------------------------- Music Search, Cached Stream, & Download

#[derive(Deserialize)]
struct SearchQuery {
    q: String,
}

async fn music_search(
    State(ctx): State<Arc<AppContext>>,
    Query(params): Query<SearchQuery>,
) -> impl IntoResponse {
    match ctx.music.search_songs(&params.q).await {
        Ok(tracks) => (StatusCode::OK, Json(json!({ "tracks": tracks }))),
        Err(e) => (StatusCode::BAD_GATEWAY, Json(json!({ "error": e, "tracks": [] }))),
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RadioQuery {
    video_id: String,
}

async fn music_radio(
    State(ctx): State<Arc<AppContext>>,
    Query(params): Query<RadioQuery>,
) -> impl IntoResponse {
    let tracks = ctx.music.fetch_radio_queue(&params.video_id).await;
    Json(json!({ "tracks": tracks }))
}

async fn music_stream(
    State(ctx): State<Arc<AppContext>>,
    AxumPath(video_id): AxumPath<String>,
    headers: HeaderMap,
) -> Response {
    // Check if permanently downloaded in state first
    let downloaded_path = {
        let st = ctx.state.read().await;
        st.downloaded_workout_tracks
            .iter()
            .find(|d| d.track.video_id == video_id)
            .map(|d| (PathBuf::from(&d.local_path), d.mime_type.clone()))
            .filter(|(p, _)| p.exists())
    };

    let (path, mime) = match downloaded_path {
        Some(pair) => pair,
        None => match ctx.music.ensure_cached_file(&video_id).await {
            Ok(pair) => pair,
            Err(err) => {
                return (StatusCode::BAD_GATEWAY, err).into_response();
            }
        },
    };

    serve_audio_with_range(&path, &mime, &headers, None).await
}

async fn music_download(
    State(ctx): State<Arc<AppContext>>,
    Json(track): Json<WorkoutTrack>,
) -> impl IntoResponse {
    match ctx.music.download_track(&track).await {
        Ok(saved) => {
            let mut st = ctx.state.write().await;
            st.downloaded_workout_tracks
                .retain(|d| d.track.video_id != track.video_id);
            st.downloaded_workout_tracks.insert(0, saved.clone());
            let _ = ctx.storage.save_state(&st);
            ctx.notify("state_updated", json!({ "at": now_ms() }));
            (
                StatusCode::OK,
                Json(json!({
                    "ok": true,
                    "downloaded": saved,
                    "folder": ctx.music.downloads_dir().to_string_lossy(),
                })),
            )
        }
        Err(err) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({ "ok": false, "error": err })),
        ),
    }
}

async fn music_download_file(
    State(ctx): State<Arc<AppContext>>,
    AxumPath(video_id): AxumPath<String>,
    headers: HeaderMap,
) -> Response {
    let found = {
        let st = ctx.state.read().await;
        st.downloaded_workout_tracks
            .iter()
            .find(|d| d.track.video_id == video_id)
            .cloned()
    };
    let Some(item) = found else {
        return (StatusCode::NOT_FOUND, "Track not downloaded").into_response();
    };
    let path = PathBuf::from(&item.local_path);
    if !path.exists() {
        return (StatusCode::NOT_FOUND, "Downloaded file missing").into_response();
    }
    let filename = path
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("voxban-track.m4a")
        .to_string();
    serve_audio_with_range(&path, &item.mime_type, &headers, Some(&filename)).await
}

async fn music_delete_download(
    State(ctx): State<Arc<AppContext>>,
    AxumPath(video_id): AxumPath<String>,
) -> impl IntoResponse {
    let mut st = ctx.state.write().await;
    if let Some(pos) = st
        .downloaded_workout_tracks
        .iter()
        .position(|d| d.track.video_id == video_id)
    {
        let removed = st.downloaded_workout_tracks.remove(pos);
        let _ = std::fs::remove_file(&removed.local_path);
        let _ = ctx.storage.save_state(&st);
        ctx.notify("state_updated", json!({ "at": now_ms() }));
        return Json(json!({ "ok": true }));
    }
    Json(json!({ "ok": false }))
}

async fn music_open_folder(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    let dir = ctx.music.downloads_dir().to_path_buf();
    let _ = std::fs::create_dir_all(&dir);
    let opened = open::that(&dir).is_ok();
    Json(json!({ "ok": opened, "path": dir.to_string_lossy() }))
}

async fn serve_audio_with_range(
    path: &PathBuf,
    mime: &str,
    headers: &HeaderMap,
    attachment_name: Option<&str>,
) -> Response {
    let Ok(mut file) = File::open(path).await else {
        return (StatusCode::NOT_FOUND, "Audio not found").into_response();
    };
    let Ok(meta) = file.metadata().await else {
        return (StatusCode::INTERNAL_SERVER_ERROR, "Metadata error").into_response();
    };
    let total = meta.len();

    let (start, end, is_partial) = if let Some(range_hdr) = headers.get(header::RANGE).and_then(|v| v.to_str().ok()) {
        if let Some(spec) = range_hdr.strip_prefix("bytes=") {
            let mut parts = spec.split('-');
            let s = parts.next().and_then(|v| v.parse::<u64>().ok()).unwrap_or(0);
            let e = parts
                .next()
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or_else(|| total.saturating_sub(1))
                .min(total.saturating_sub(1));
            if s <= e && s < total {
                (s, e, true)
            } else {
                (0, total.saturating_sub(1), false)
            }
        } else {
            (0, total.saturating_sub(1), false)
        }
    } else {
        (0, total.saturating_sub(1), false)
    };

    let len = end.saturating_sub(start) + 1;
    let _ = file.seek(SeekFrom::Start(start)).await;
    let mut buf = vec![0u8; len as usize];
    if file.read_exact(&mut buf).await.is_err() {
        return (StatusCode::INTERNAL_SERVER_ERROR, "Read error").into_response();
    }

    let mut builder = Response::builder()
        .status(if is_partial { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK })
        .header(header::CONTENT_TYPE, mime)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_LENGTH, len.to_string());

    if is_partial {
        builder = builder.header(
            header::CONTENT_RANGE,
            format!("bytes {start}-{end}/{total}"),
        );
    }
    if let Some(name) = attachment_name {
        builder = builder.header(
            header::CONTENT_DISPOSITION,
            format!("attachment; filename=\"{}\"", name.replace('"', "")),
        );
    }
    builder
        .body(Body::from(buf))
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

// ---------------------------------------------------------------- Password Vault

async fn current_biometric_status(ctx: &Arc<AppContext>) -> biometrics::BiometricStatus {
    let data_dir = ctx.storage.data_dir.clone();
    let exists = ctx.storage.vault_exists();
    tokio::task::spawn_blocking(move || biometrics::status(&data_dir, exists))
        .await
        .unwrap_or(biometrics::BiometricStatus {
            available: false,
            enrolled: false,
            label: "Fingerprint".into(),
        })
}

async fn vault_status(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    let exists = ctx.storage.vault_exists();
    let unlocked = ctx.unlocked_vault_passphrase.read().await.is_some();
    let biometric = current_biometric_status(&ctx).await;
    Json(json!({
        "exists": exists,
        "unlocked": unlocked,
        "biometric": biometric,
    }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VaultUnlockReq {
    passphrase: String,
    #[serde(default)]
    enable_biometric: bool,
}

async fn vault_unlock(
    State(ctx): State<Arc<AppContext>>,
    Json(req): Json<VaultUnlockReq>,
) -> impl IntoResponse {
    if req.passphrase.trim().is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "ok": false, "error": "Master password is required." })),
        );
    }

    let existed = ctx.storage.vault_exists();
    match ctx.storage.unlock_vault(&req.passphrase) {
        Ok(entries) => {
            if !existed {
                if let Err(err) = ctx.storage.save_vault(&req.passphrase, &entries) {
                    return (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        Json(json!({ "ok": false, "error": err })),
                    );
                }
            }
            *ctx.unlocked_vault_passphrase.write().await = Some(req.passphrase.clone());

            let mut bio_warning: Option<String> = None;
            if req.enable_biometric {
                let data_dir = ctx.storage.data_dir.clone();
                let pass = req.passphrase.clone();
                let enroll_res = tokio::task::spawn_blocking(move || {
                    let hw = biometrics::detect_hardware();
                    if !hw.available {
                        return Err("No fingerprint sensor is available on this PC.".to_string());
                    }
                    biometrics::verify_user(&format!(
                        "Enable {} for your Voxban Password Vault",
                        hw.label
                    ))?;
                    biometrics::store_enrolled_secret(&data_dir, &pass)
                })
                .await
                .unwrap_or_else(|_| Err("Biometric verification failed.".to_string()));

                if let Err(err) = enroll_res {
                    bio_warning = Some(err);
                }
            }

            let biometric = current_biometric_status(&ctx).await;
            (
                StatusCode::OK,
                Json(json!({
                    "ok": true,
                    "entries": entries,
                    "biometric": biometric,
                    "biometricWarning": bio_warning,
                })),
            )
        }
        Err(err) => (StatusCode::UNAUTHORIZED, Json(json!({ "ok": false, "error": err }))),
    }
}

async fn vault_lock(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    *ctx.unlocked_vault_passphrase.write().await = None;
    let biometric = current_biometric_status(&ctx).await;
    Json(json!({ "ok": true, "biometric": biometric }))
}

#[derive(Deserialize)]
struct VaultSaveReq {
    #[serde(default)]
    passphrase: Option<String>,
    entries: Vec<PasswordEntry>,
}

async fn vault_save(
    State(ctx): State<Arc<AppContext>>,
    Json(req): Json<VaultSaveReq>,
) -> impl IntoResponse {
    let resolved = match req.passphrase.filter(|s| !s.is_empty()) {
        Some(p) => p,
        None => match ctx.unlocked_vault_passphrase.read().await.clone() {
            Some(p) => p,
            None => {
                return (
                    StatusCode::UNAUTHORIZED,
                    Json(json!({ "ok": false, "error": "Vault is locked. Unlock it first." })),
                );
            }
        },
    };
    match ctx.storage.save_vault(&resolved, &req.entries) {
        Ok(()) => {
            *ctx.unlocked_vault_passphrase.write().await = Some(resolved);
            (StatusCode::OK, Json(json!({ "ok": true })))
        }
        Err(err) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "ok": false, "error": err }))),
    }
}

async fn vault_biometric_unlock(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    if !ctx.storage.vault_exists() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "ok": false, "error": "Create your vault with a master password first." })),
        );
    }

    let data_dir = ctx.storage.data_dir.clone();
    let auth_res = tokio::task::spawn_blocking(move || {
        let hw = biometrics::detect_hardware();
        if !hw.available {
            return Err("No fingerprint sensor is available on this PC.".to_string());
        }
        if !biometrics::has_enrolled_secret(&data_dir) {
            return Err(format!(
                "{} is not enabled for this vault yet. Unlock once with your master password to turn it on.",
                hw.label
            ));
        }
        biometrics::verify_user(&format!("Unlock your Voxban Password Vault with {}", hw.label))?;
        biometrics::load_enrolled_secret(&data_dir)
    })
    .await
    .unwrap_or_else(|_| Err("Biometric verification failed.".to_string()));

    let passphrase = match auth_res {
        Ok(p) => p,
        Err(err) => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(json!({ "ok": false, "error": err })),
            );
        }
    };

    match ctx.storage.unlock_vault(&passphrase) {
        Ok(entries) => {
            *ctx.unlocked_vault_passphrase.write().await = Some(passphrase);
            let biometric = current_biometric_status(&ctx).await;
            (
                StatusCode::OK,
                Json(json!({
                    "ok": true,
                    "entries": entries,
                    "biometric": biometric,
                })),
            )
        }
        Err(_) => {
            // Saved OS credential no longer matches vault.enc (e.g. vault was replaced)
            biometrics::clear_enrolled_secret(&ctx.storage.data_dir);
            let biometric = current_biometric_status(&ctx).await;
            (
                StatusCode::UNAUTHORIZED,
                Json(json!({
                    "ok": false,
                    "error": "Saved biometric key no longer matches this vault. Unlock with your master password to re-link.",
                    "biometric": biometric,
                })),
            )
        }
    }
}

#[derive(Deserialize)]
struct VaultBioEnableReq {
    #[serde(default)]
    passphrase: Option<String>,
}

async fn vault_biometric_enable(
    State(ctx): State<Arc<AppContext>>,
    Json(req): Json<VaultBioEnableReq>,
) -> impl IntoResponse {
    let resolved = match req.passphrase.filter(|s| !s.is_empty()) {
        Some(p) => p,
        None => match ctx.unlocked_vault_passphrase.read().await.clone() {
            Some(p) => p,
            None => {
                return (
                    StatusCode::UNAUTHORIZED,
                    Json(json!({ "ok": false, "error": "Unlock your vault first before enabling fingerprint unlock." })),
                );
            }
        },
    };

    // Confirm passphrase matches the vault before saving into OS Keychain / DPAPI
    if let Err(err) = ctx.storage.unlock_vault(&resolved) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({ "ok": false, "error": err })),
        );
    }
    if !ctx.storage.vault_exists() {
        if let Err(err) = ctx.storage.save_vault(&resolved, &[]) {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "ok": false, "error": err })),
            );
        }
    }

    let data_dir = ctx.storage.data_dir.clone();
    let res = tokio::task::spawn_blocking(move || {
        let hw = biometrics::detect_hardware();
        if !hw.available {
            return Err("No fingerprint sensor detected on this PC.".to_string());
        }
        biometrics::verify_user(&format!("Enable {} for your Voxban Password Vault", hw.label))?;
        biometrics::store_enrolled_secret(&data_dir, &resolved)
    })
    .await
    .unwrap_or_else(|_| Err("Biometric setup failed.".to_string()));

    let biometric = current_biometric_status(&ctx).await;
    match res {
        Ok(()) => (StatusCode::OK, Json(json!({ "ok": true, "biometric": biometric }))),
        Err(err) => (
            StatusCode::UNAUTHORIZED,
            Json(json!({ "ok": false, "error": err, "biometric": biometric })),
        ),
    }
}

async fn vault_biometric_disable(State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    let data_dir = ctx.storage.data_dir.clone();
    let _ = tokio::task::spawn_blocking(move || biometrics::clear_enrolled_secret(&data_dir)).await;
    let biometric = current_biometric_status(&ctx).await;
    Json(json!({ "ok": true, "biometric": biometric }))
}

async fn vault_generate() -> impl IntoResponse {
    const CHARSET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*()-_=+";
    let mut rng = rand::thread_rng();
    let pw: String = (0..20)
        .map(|_| CHARSET[rng.gen_range(0..CHARSET.len())] as char)
        .collect();
    Json(json!({ "password": pw }))
}

// ---------------------------------------------------------------- Support Tickets & Updates

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SupportFormatReq {
    ticket: SupportTicket,
    #[serde(default)]
    is_follow_up: bool,
    #[serde(default)]
    save: bool,
}

async fn support_format(
    State(ctx): State<Arc<AppContext>>,
    Json(mut req): Json<SupportFormatReq>,
) -> impl IntoResponse {
    if req.ticket.id.trim().is_empty() {
        req.ticket.id = support::generate_ticket_id();
    }
    let user_name = ctx.state.read().await.settings.user_name.clone();
    let formatted = support::format_ticket_email(&req.ticket, &user_name, req.is_follow_up);

    if req.save {
        let mut st = ctx.state.write().await;
        st.support_tickets.retain(|t| t.id != req.ticket.id);
        st.support_tickets.insert(0, req.ticket.clone());
        let _ = ctx.storage.save_state(&st);
        ctx.notify("state_updated", json!({ "at": now_ms() }));
    }

    Json(json!({
        "ticket": req.ticket,
        "email": formatted,
    }))
}

async fn updates_check() -> impl IntoResponse {
    match updater::check_github_release().await {
        Ok(info) => (StatusCode::OK, Json(json!({ "ok": true, "release": info }))),
        Err(err) => (StatusCode::BAD_GATEWAY, Json(json!({ "ok": false, "error": err }))),
    }
}

// ---------------------------------------------------------------- WebSocket live updates

async fn ws_handler(ws: WebSocketUpgrade, State(ctx): State<Arc<AppContext>>) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_ws(socket, ctx))
}

async fn handle_ws(mut socket: WebSocket, ctx: Arc<AppContext>) {
    let mut rx = ctx.events.subscribe();
    while let Ok(msg) = rx.recv().await {
        if socket.send(Message::Text(msg.into())).await.is_err() {
            break;
        }
    }
}

// ---------------------------------------------------------------- Frontend static serving

async fn serve_frontend(State(ctx): State<Arc<AppContext>>, uri: axum::http::Uri) -> Response {
    let raw_path = uri.path().trim_start_matches('/');
    let rel_path = if raw_path.is_empty() { "index.html" } else { raw_path };

    // 1. Embedded UI (single-binary Windows release mode)
    #[cfg(feature = "embed-ui")]
    {
        if let Some(asset) = EmbeddedFrontend::get(rel_path).or_else(|| EmbeddedFrontend::get("index.html")) {
            let mime = mime_guess::from_path(rel_path).first_or_octet_stream();
            return Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, mime.as_ref())
                .body(Body::from(asset.data.into_owned()))
                .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response());
        }
    }

    // 2. Disk UI (development & standalone runtime mode)
    if let Some(dir) = &ctx.frontend_dir {
        let candidate = dir.join(rel_path);
        let file_path = if candidate.is_file() {
            candidate
        } else {
            dir.join("index.html")
        };
        if let Ok(bytes) = tokio::fs::read(&file_path).await {
            let mime = mime_guess::from_path(&file_path).first_or_octet_stream();
            return Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, mime.as_ref())
                .body(Body::from(bytes))
                .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response());
        }
    }

    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
        "<h1>Voxban Rust Backend Running</h1><p>Build the frontend in <code>desktop/frontend</code> or run via Vite dev server.</p>",
    )
        .into_response()
}
