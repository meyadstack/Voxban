use crate::models::{now_ms, DownloadedTrack, WorkoutTrack};
use reqwest::Client;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::fs;
use tokio::io::AsyncWriteExt;
use tokio::sync::RwLock;

const SEARCH_CACHE_TTL: Duration = Duration::from_secs(15 * 60);
const STREAM_CACHE_TTL: Duration = Duration::from_secs(3 * 60 * 60);
const VISITOR_CACHE_TTL: Duration = Duration::from_secs(6 * 60 * 60);

#[derive(Debug, Clone)]
pub struct ResolvedStream {
    pub url: String,
    pub mime_type: String,
    pub content_length: Option<u64>,
    pub user_agent: String,
    pub cached_at: Instant,
}

pub struct MusicEngine {
    http: Client,
    cache_dir: PathBuf,
    downloads_dir: PathBuf,
    visitor_data: RwLock<Option<(Instant, String)>>,
    search_cache: RwLock<HashMap<String, (Instant, Vec<WorkoutTrack>)>>,
    stream_cache: RwLock<HashMap<String, ResolvedStream>>,
}

impl MusicEngine {
    pub fn new(cache_dir: PathBuf, downloads_dir: PathBuf) -> Arc<Self> {
        let http = Client::builder()
            .timeout(Duration::from_secs(45))
            .build()
            .expect("HTTP client");
        Arc::new(Self {
            http,
            cache_dir,
            downloads_dir,
            visitor_data: RwLock::new(None),
            search_cache: RwLock::new(HashMap::new()),
            stream_cache: RwLock::new(HashMap::new()),
        })
    }

    pub fn http_client(&self) -> &Client {
        &self.http
    }

    async fn get_visitor_data(&self, force_refresh: bool) -> String {
        if !force_refresh {
            let guard = self.visitor_data.read().await;
            if let Some((at, vd)) = guard.as_ref() {
                if at.elapsed() < VISITOR_CACHE_TTL && !vd.is_empty() {
                    return vd.clone();
                }
            }
        }
        let body = json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20260707.12.00"
                }
            }
        });
        if let Ok(resp) = self
            .http
            .post("https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            .json(&body)
            .send()
            .await
        {
            if let Ok(val) = resp.json::<Value>().await {
                if let Some(vd) = val.pointer("/responseContext/visitorData").and_then(|v| v.as_str()) {
                    if !vd.is_empty() {
                        *self.visitor_data.write().await = Some((Instant::now(), vd.to_string()));
                        return vd.to_string();
                    }
                }
            }
        }
        String::new()
    }

    pub fn downloads_dir(&self) -> &Path {
        &self.downloads_dir
    }

    pub async fn search_songs(&self, query: &str) -> Result<Vec<WorkoutTrack>, String> {
        let key = query.trim().to_lowercase();
        if key.is_empty() {
            return Ok(vec![]);
        }

        {
            let guard = self.search_cache.read().await;
            if let Some((at, tracks)) = guard.get(&key) {
                if at.elapsed() < SEARCH_CACHE_TTL {
                    return Ok(tracks.clone());
                }
            }
        }

        let payload = json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20251001.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "query": query.trim(),
            "params": "EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D"
        });

        let resp = self
            .http
            .post("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .json(&payload)
            .send()
            .await
            .map_err(|e| format!("Music search network error: {e}"))?;

        if !resp.status().is_success() {
            return Err(format!("Music search returned HTTP {}", resp.status()));
        }

        let root: Value = resp
            .json()
            .await
            .map_err(|e| format!("Invalid search response: {e}"))?;
        let tracks = parse_search_tracks(&root);

        if !tracks.is_empty() {
            let mut guard = self.search_cache.write().await;
            guard.insert(key, (Instant::now(), tracks.clone()));
        }
        Ok(tracks)
    }

    pub async fn fetch_radio_queue(&self, video_id: &str) -> Vec<WorkoutTrack> {
        let payload = json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20251001.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": video_id,
            "playlistId": format!("RDAMVM{video_id}"),
            "isAudioOnly": true
        });

        let Ok(resp) = self
            .http
            .post("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Origin", "https://music.youtube.com")
            .json(&payload)
            .send()
            .await
        else {
            return vec![];
        };

        let Ok(root) = resp.json::<Value>().await else {
            return vec![];
        };
        parse_radio_tracks(&root, video_id)
    }

    /// Resolves and live-verifies a direct audio stream URL using InnerTube clients
    /// (VISIONOS 1.02 -> VISIONOS 0.1 -> ANDROID_VR 1.65.10 -> IOS 20.10.4) with fresh visitorData.
    pub async fn resolve_audio_stream(&self, video_id: &str, force_refresh: bool) -> Result<ResolvedStream, String> {
        if !force_refresh {
            let guard = self.stream_cache.read().await;
            if let Some(s) = guard.get(video_id) {
                if s.cached_at.elapsed() < STREAM_CACHE_TTL {
                    return Ok(s.clone());
                }
            }
        }

        let visitor_data = self.get_visitor_data(force_refresh).await;

        let clients: Vec<(&str, &str, &str, Value)> = vec![
            (
                "101",
                "1.02",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                json!({
                    "clientName": "VISIONOS",
                    "clientVersion": "1.02",
                    "osName": "visionOS",
                    "osVersion": "26.5.23O471",
                    "deviceMake": "Apple",
                    "deviceModel": "RealityDevice17,1",
                    "hl": "en",
                    "gl": "US"
                }),
            ),
            (
                "101",
                "0.1",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
                json!({
                    "clientName": "VISIONOS",
                    "clientVersion": "0.1",
                    "osName": "VISION_OS",
                    "osVersion": "1.3",
                    "deviceMake": "Apple",
                    "deviceModel": "RealityDevice14,1",
                    "platform": "MOBILE",
                    "hl": "en",
                    "gl": "US"
                }),
            ),
            (
                "28",
                "1.65.10",
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                json!({
                    "clientName": "ANDROID_VR",
                    "clientVersion": "1.65.10",
                    "deviceMake": "Oculus",
                    "deviceModel": "Quest 3",
                    "osName": "Android",
                    "osVersion": "12L",
                    "androidSdkVersion": 32,
                    "hl": "en",
                    "gl": "US"
                }),
            ),
            (
                "5",
                "20.10.4",
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                json!({
                    "clientName": "IOS",
                    "clientVersion": "20.10.4",
                    "deviceMake": "Apple",
                    "deviceModel": "iPhone16,2",
                    "osName": "iPhone",
                    "osVersion": "18.3.2.22D82",
                    "hl": "en",
                    "gl": "US"
                }),
            ),
        ];

        let mut last_err = String::from("No playable audio format found");
        for (client_id, client_ver, ua, mut client_ctx) in clients {
            if !visitor_data.is_empty() {
                if let Some(obj) = client_ctx.as_object_mut() {
                    obj.insert("visitorData".into(), json!(visitor_data));
                }
            }
            let body = json!({
                "context": { "client": client_ctx },
                "videoId": video_id,
                "contentCheckOk": true,
                "racyCheckOk": true
            });
            let mut req = self
                .http
                .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", ua)
                .header("X-YouTube-Client-Name", client_id)
                .header("X-YouTube-Client-Version", client_ver)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/");
            if !visitor_data.is_empty() {
                req = req.header("X-Goog-Visitor-Id", &visitor_data);
            }
            let res = req.json(&body).send().await;

            let Ok(resp) = res else {
                last_err = format!("Player request failed: {}", res.unwrap_err());
                continue;
            };
            if !resp.status().is_success() {
                last_err = format!("Player returned HTTP {}", resp.status());
                continue;
            }
            let Ok(json_val) = resp.json::<Value>().await else {
                continue;
            };
            let candidates = rank_audio_formats(&json_val, ua);
            for candidate in candidates.into_iter().take(3) {
                if self.probe_stream(&candidate).await {
                    let mut guard = self.stream_cache.write().await;
                    guard.insert(video_id.to_string(), candidate.clone());
                    return Ok(candidate);
                } else {
                    last_err = "Audio stream rejected by host (HTTP 403); trying next client".into();
                }
            }
        }

        Err(last_err)
    }

    /// Verifies both the head AND tail byte ranges of a candidate stream URL before caching/serving it.
    async fn probe_stream(&self, stream: &ResolvedStream) -> bool {
        let head = self
            .http
            .get(&stream.url)
            .header("User-Agent", &stream.user_agent)
            .header("Range", "bytes=0-1023")
            .send()
            .await;
        let head_ok = match head {
            Ok(r) => r.status().is_success() || r.status().as_u16() == 206,
            Err(_) => false,
        };
        if !head_ok {
            return false;
        }
        if let Some(total) = stream.content_length.filter(|&n| n > 4096) {
            let tail_start = total - 1024;
            let tail_end = total - 1;
            let tail = self
                .http
                .get(&stream.url)
                .header("User-Agent", &stream.user_agent)
                .header("Range", format!("bytes={tail_start}-{tail_end}"))
                .send()
                .await;
            return match tail {
                Ok(r) => r.status().is_success() || r.status().as_u16() == 206,
                Err(_) => false,
            };
        }
        true
    }

    pub async fn invalidate_stream(&self, video_id: &str) {
        self.stream_cache.write().await.remove(video_id);
    }

    pub fn cached_audio_path(&self, video_id: &str) -> Option<(PathBuf, String)> {
        // First check permanent downloaded tracks, then disk cache
        for dir in [&self.downloads_dir, &self.cache_dir] {
            for (ext, mime) in [
                ("m4a", "audio/mp4"),
                ("webm", "audio/webm"),
                ("opus", "audio/webm"),
                ("mp3", "audio/mpeg"),
                ("ogg", "audio/ogg"),
            ] {
                let candidate = dir.join(format!("{video_id}.{ext}"));
                if let Ok(meta) = std::fs::metadata(&candidate) {
                    if meta.len() > 1024 {
                        return Some((candidate, mime.to_string()));
                    }
                }
            }
        }
        None
    }

    /// Ensures the audio track is cached on local disk (up to 150 MB LRU) and returns its file path & mime.
    pub async fn ensure_cached_file(&self, video_id: &str) -> Result<(PathBuf, String), String> {
        if let Some(found) = self.cached_audio_path(video_id) {
            return Ok(found);
        }

        let stream = self.resolve_audio_stream(video_id, false).await?;
        let ext = extension_for_mime(&stream.mime_type);
        let dest = self.cache_dir.join(format!("{video_id}.{ext}"));
        let tmp = self.cache_dir.join(format!("{video_id}.{ext}.part"));

        self.download_stream_to_path(&stream, &tmp).await?;
        fs::rename(&tmp, &dest)
            .await
            .map_err(|e| format!("Failed saving cache file: {e}"))?;

        let _ = self.trim_cache_dir(150 * 1024 * 1024).await;
        Ok((dest, stream.mime_type))
    }

    /// Permanently downloads a workout track to the user's Music/Voxban directory (and registers it for offline playback).
    pub async fn download_track(&self, track: &WorkoutTrack) -> Result<DownloadedTrack, String> {
        let stream = self.resolve_audio_stream(&track.video_id, false).await?;
        let ext = extension_for_mime(&stream.mime_type);
        let clean_name = sanitize_filename(&if track.artist.trim().is_empty() {
            track.title.clone()
        } else {
            format!("{} - {}", track.title, track.artist)
        });

        let _ = fs::create_dir_all(&self.downloads_dir).await;
        // Save with human-friendly filename in Music/Voxban/ AND videoId index copy/symlink or direct path
        let friendly_path = self.downloads_dir.join(format!("{clean_name}.{ext}"));
        let tmp = self.downloads_dir.join(format!(".{}.{ext}.part", track.video_id));

        // Reuse cached file if already on disk!
        if let Some((cached_path, cached_mime)) = self.cached_audio_path(&track.video_id) {
            fs::copy(&cached_path, &friendly_path)
                .await
                .map_err(|e| format!("Failed copying cached audio: {e}"))?;
            let size = fs::metadata(&friendly_path).await.map(|m| m.len()).unwrap_or(0);
            return Ok(DownloadedTrack {
                track: track.clone(),
                local_path: friendly_path.to_string_lossy().to_string(),
                mime_type: cached_mime,
                size_bytes: size,
                downloaded_at: now_ms(),
                exported_to_music_folder: true,
            });
        }

        self.download_stream_to_path(&stream, &tmp).await?;
        fs::rename(&tmp, &friendly_path)
            .await
            .map_err(|e| format!("Failed finalizing download: {e}"))?;
        let size = fs::metadata(&friendly_path)
            .await
            .map(|m| m.len())
            .unwrap_or(0);

        Ok(DownloadedTrack {
            track: track.clone(),
            local_path: friendly_path.to_string_lossy().to_string(),
            mime_type: stream.mime_type,
            size_bytes: size,
            downloaded_at: now_ms(),
            exported_to_music_folder: true,
        })
    }

    async fn download_stream_to_path(&self, stream: &ResolvedStream, target: &Path) -> Result<(), String> {
        let chunk_size: u64 = 512 * 1024;
        let mut file = fs::File::create(target)
            .await
            .map_err(|e| format!("Could not create audio file: {e}"))?;

        if let Some(total) = stream.content_length.filter(|&n| n > 0) {
            let mut pos: u64 = 0;
            while pos < total {
                let end = (pos + chunk_size - 1).min(total - 1);
                let resp = self
                    .http
                    .get(&stream.url)
                    .header("User-Agent", &stream.user_agent)
                    .header("Range", format!("bytes={pos}-{end}"))
                    .send()
                    .await
                    .map_err(|e| format!("Audio download error: {e}"))?;
                if !resp.status().is_success() && resp.status().as_u16() != 206 {
                    return Err(format!("Audio stream returned HTTP {}", resp.status()));
                }
                let bytes = resp.bytes().await.map_err(|e| format!("Read error: {e}"))?;
                if bytes.is_empty() {
                    break;
                }
                file.write_all(&bytes)
                    .await
                    .map_err(|e| format!("Write error: {e}"))?;
                pos += bytes.len() as u64;
            }
        } else {
            let resp = self
                .http
                .get(&stream.url)
                .header("User-Agent", &stream.user_agent)
                .send()
                .await
                .map_err(|e| format!("Audio download error: {e}"))?;
            if !resp.status().is_success() {
                return Err(format!("Audio stream returned HTTP {}", resp.status()));
            }
            let bytes = resp.bytes().await.map_err(|e| format!("Read error: {e}"))?;
            file.write_all(&bytes)
                .await
                .map_err(|e| format!("Write error: {e}"))?;
        }
        file.flush().await.map_err(|e| e.to_string())?;
        Ok(())
    }

    async fn trim_cache_dir(&self, max_bytes: u64) -> std::io::Result<()> {
        let mut entries = Vec::new();
        let mut rd = fs::read_dir(&self.cache_dir).await?;
        let mut total = 0u64;
        while let Some(e) = rd.next_entry().await? {
            if let Ok(meta) = e.metadata().await {
                if meta.is_file() {
                    let len = meta.len();
                    total += len;
                    let modified = meta.modified().ok();
                    entries.push((e.path(), len, modified));
                }
            }
        }
        if total <= max_bytes {
            return Ok(());
        }
        entries.sort_by_key(|(_, _, m)| *m);
        for (path, len, _) in entries {
            if total <= max_bytes {
                break;
            }
            if fs::remove_file(&path).await.is_ok() {
                total = total.saturating_sub(len);
            }
        }
        Ok(())
    }
}

fn rank_audio_formats(root: &Value, ua: &str) -> Vec<ResolvedStream> {
    let Some(formats) = root
        .pointer("/streamingData/adaptiveFormats")
        .and_then(|v| v.as_array())
    else {
        return Vec::new();
    };

    let mut ranked: Vec<(u64, ResolvedStream)> = Vec::new();
    for f in formats {
        let mime = f.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
        if !mime.starts_with("audio/") {
            continue;
        }
        let url = f.get("url").and_then(|v| v.as_str()).unwrap_or("");
        if url.is_empty() {
            continue;
        }
        let bitrate = f.get("bitrate").and_then(|v| v.as_u64()).unwrap_or(0);
        let content_length = f
            .get("contentLength")
            .and_then(|v| v.as_str())
            .and_then(|s| s.parse::<u64>().ok())
            .or_else(|| f.get("contentLength").and_then(|v| v.as_u64()));

        // Strongly prefer audio/mp4 (AAC M4A) — universally playable in macOS WKWebView, Windows WebView2, Safari & Chrome!
        let bonus = if mime.starts_with("audio/mp4") { 500_000 } else { 0 };
        ranked.push((
            bitrate + bonus,
            ResolvedStream {
                url: url.to_string(),
                mime_type: mime.split(';').next().unwrap_or("audio/mp4").trim().to_string(),
                content_length,
                user_agent: ua.to_string(),
                cached_at: Instant::now(),
            },
        ));
    }
    ranked.sort_by(|a, b| b.0.cmp(&a.0));
    ranked.into_iter().map(|(_, s)| s).collect()
}

pub fn parse_search_tracks(root: &Value) -> Vec<WorkoutTrack> {
    let mut out = Vec::new();
    let mut seen = std::collections::HashSet::new();
    collect_responsive_items(root, &mut out, &mut seen);
    out
}

fn collect_responsive_items(
    node: &Value,
    out: &mut Vec<WorkoutTrack>,
    seen: &mut std::collections::HashSet<String>,
) {
    if out.len() >= 30 {
        return;
    }
    match node {
        Value::Object(map) => {
            if let Some(item) = map.get("musicResponsiveListItemRenderer") {
                if let Some(track) = parse_responsive_item(item) {
                    if seen.insert(track.video_id.clone()) {
                        out.push(track);
                    }
                }
            }
            for v in map.values() {
                collect_responsive_items(v, out, seen);
            }
        }
        Value::Array(arr) => {
            for v in arr {
                collect_responsive_items(v, out, seen);
            }
        }
        _ => {}
    }
}

fn parse_responsive_item(item: &Value) -> Option<WorkoutTrack> {
    let video_id = item
        .pointer("/playlistItemData/videoId")
        .and_then(|v| v.as_str())
        .or_else(|| {
            item.pointer("/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId")
                .and_then(|v| v.as_str())
        })?;
    if video_id.is_empty() {
        return None;
    }

    let cols = item.get("flexColumns").and_then(|v| v.as_array())?;
    let title = cols
        .first()
        .and_then(|c| c.pointer("/musicResponsiveListItemFlexColumnRenderer/text/runs"))
        .and_then(|v| v.as_array())
        .map(|runs| {
            runs.iter()
                .filter_map(|r| r.get("text").and_then(|t| t.as_str()))
                .collect::<String>()
        })
        .unwrap_or_default();
    if title.trim().is_empty() {
        return None;
    }

    let meta_runs: Vec<String> = cols
        .get(1)
        .and_then(|c| c.pointer("/musicResponsiveListItemFlexColumnRenderer/text/runs"))
        .and_then(|v| v.as_array())
        .map(|runs| {
            runs.iter()
                .filter_map(|r| r.get("text").and_then(|t| t.as_str()))
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty() && s != "•" && s != "·")
                .collect()
        })
        .unwrap_or_default();

    let mut duration_sec = 0i64;
    let mut artist = String::new();
    for part in &meta_runs {
        if let Some(secs) = parse_duration_text(part) {
            duration_sec = secs;
        } else if artist.is_empty()
            && !matches!(part.to_lowercase().as_str(), "song" | "video" | "album" | "single")
        {
            artist = part.clone();
        }
    }

    let thumbnail_url = item
        .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails")
        .and_then(|v| v.as_array())
        .and_then(|arr| arr.last())
        .and_then(|t| t.get("url"))
        .and_then(|u| u.as_str())
        .unwrap_or("")
        .to_string();

    Some(WorkoutTrack {
        video_id: video_id.to_string(),
        title: title.trim().to_string(),
        artist,
        album: String::new(),
        duration_sec,
        thumbnail_url,
    })
}

fn parse_radio_tracks(root: &Value, seed_video_id: &str) -> Vec<WorkoutTrack> {
    let mut out = Vec::new();
    let mut seen = std::collections::HashSet::new();
    seen.insert(seed_video_id.to_string());
    collect_playlist_panel_items(root, &mut out, &mut seen);
    out
}

fn collect_playlist_panel_items(
    node: &Value,
    out: &mut Vec<WorkoutTrack>,
    seen: &mut std::collections::HashSet<String>,
) {
    if out.len() >= 20 {
        return;
    }
    match node {
        Value::Object(map) => {
            if let Some(item) = map.get("playlistPanelVideoRenderer") {
                if let Some(vid) = item.get("videoId").and_then(|v| v.as_str()) {
                    if !vid.is_empty() && seen.insert(vid.to_string()) {
                        let title = item
                            .pointer("/title/runs/0/text")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let artist = item
                            .pointer("/longBylineText/runs/0/text")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let dur = item
                            .pointer("/lengthText/runs/0/text")
                            .and_then(|v| v.as_str())
                            .and_then(parse_duration_text)
                            .unwrap_or(0);
                        let thumb = item
                            .pointer("/thumbnail/thumbnails")
                            .and_then(|v| v.as_array())
                            .and_then(|a| a.last())
                            .and_then(|t| t.get("url"))
                            .and_then(|u| u.as_str())
                            .unwrap_or("")
                            .to_string();
                        if !title.is_empty() {
                            out.push(WorkoutTrack {
                                video_id: vid.to_string(),
                                title,
                                artist,
                                album: String::new(),
                                duration_sec: dur,
                                thumbnail_url: thumb,
                            });
                        }
                    }
                }
            }
            for v in map.values() {
                collect_playlist_panel_items(v, out, seen);
            }
        }
        Value::Array(arr) => {
            for v in arr {
                collect_playlist_panel_items(v, out, seen);
            }
        }
        _ => {}
    }
}

pub fn parse_duration_text(text: &str) -> Option<i64> {
    let parts: Vec<&str> = text.trim().split(':').collect();
    if parts.len() < 2 || parts.len() > 3 {
        return None;
    }
    let nums: Option<Vec<i64>> = parts.iter().map(|p| p.parse::<i64>().ok()).collect();
    let nums = nums?;
    match nums.as_slice() {
        [m, s] => Some(m * 60 + s),
        [h, m, s] => Some(h * 3600 + m * 60 + s),
        _ => None,
    }
}

pub fn extension_for_mime(mime: &str) -> &'static str {
    let lower = mime.to_lowercase();
    if lower.contains("mp4") || lower.contains("m4a") || lower.contains("aac") {
        "m4a"
    } else if lower.contains("mpeg") || lower.contains("mp3") {
        "mp3"
    } else if lower.contains("ogg") {
        "ogg"
    } else {
        "webm"
    }
}

pub fn sanitize_filename(raw: &str) -> String {
    let cleaned: String = raw
        .chars()
        .map(|c| match c {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => ' ',
            _ => c,
        })
        .collect();
    let compact = cleaned.split_whitespace().collect::<Vec<_>>().join(" ");
    let truncated: String = compact.chars().take(80).collect();
    if truncated.is_empty() {
        "voxban-track".to_string()
    } else {
        truncated
    }
}
