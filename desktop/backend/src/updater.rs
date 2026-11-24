use crate::models::{APP_VERSION, GITHUB_API_LATEST_URL, GITHUB_RELEASES_URL};
use futures_util::StreamExt;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::fs;
use tokio::io::AsyncWriteExt;
use tokio::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReleaseInfo {
    pub current_version: String,
    pub latest_tag: String,
    pub latest_version: String,
    pub update_available: bool,
    pub title: String,
    pub notes: String,
    pub html_url: String,
    pub apk_download_url: Option<String>,
    pub windows_download_url: Option<String>,
    pub mac_download_url: Option<String>,
    pub recommended_download_url: Option<String>,
    pub recommended_asset_name: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInstallProgress {
    /// "idle" | "downloading" | "installing" | "restarting" | "ready" | "error"
    pub phase: String,
    pub version: String,
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
    pub message: String,
    pub file_path: Option<String>,
    pub error: Option<String>,
}

impl Default for UpdateInstallProgress {
    fn default() -> Self {
        Self {
            phase: "idle".into(),
            version: String::new(),
            downloaded_bytes: 0,
            total_bytes: 0,
            message: String::new(),
            file_path: None,
            error: None,
        }
    }
}

pub async fn check_github_release() -> Result<ReleaseInfo, String> {
    let http = Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;
    let resp = http
        .get(GITHUB_API_LATEST_URL)
        .header("User-Agent", format!("Voxban-Desktop/{APP_VERSION}"))
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|e| format!("GitHub check failed: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("GitHub returned HTTP {}", resp.status()));
    }
    let val: Value = resp.json().await.map_err(|e| e.to_string())?;
    Ok(parse_release_json(&val, std::env::consts::OS, std::env::consts::ARCH))
}

pub fn parse_release_json(val: &Value, os: &str, arch: &str) -> ReleaseInfo {
    let tag = val.get("tag_name").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let latest_version = tag.trim_start_matches(['v', 'V']).to_string();
    let title = val.get("name").and_then(|v| v.as_str()).unwrap_or(&tag).to_string();
    let notes = val.get("body").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let html_url = val
        .get("html_url")
        .and_then(|v| v.as_str())
        .unwrap_or(GITHUB_RELEASES_URL)
        .to_string();

    let mut apk_url: Option<String> = None;
    let mut win_setup: Option<(String, String)> = None;
    let mut win_msi: Option<(String, String)> = None;
    let mut win_portable: Option<(String, String)> = None;
    let mut mac_arm64: Option<(String, String)> = None;
    let mut mac_x64: Option<(String, String)> = None;
    let mut mac_any: Option<(String, String)> = None;

    if let Some(assets) = val.get("assets").and_then(|v| v.as_array()) {
        for a in assets {
            let raw_name = a.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let name = raw_name.to_lowercase();
            let Some(dl) = a.get("browser_download_url").and_then(|v| v.as_str()).map(|s| s.to_string()) else {
                continue;
            };
            if name.ends_with(".apk") && apk_url.is_none() {
                apk_url = Some(dl);
            } else if name.ends_with(".dmg") {
                if (name.contains("arm64") || name.contains("aarch64")) && mac_arm64.is_none() {
                    mac_arm64 = Some((raw_name, dl));
                } else if (name.contains("x64") || name.contains("x86_64") || name.contains("intel")) && mac_x64.is_none() {
                    mac_x64 = Some((raw_name, dl));
                } else if mac_any.is_none() {
                    mac_any = Some((raw_name, dl));
                }
            } else if name.ends_with(".exe") {
                if name.contains("setup") && win_setup.is_none() {
                    win_setup = Some((raw_name, dl));
                } else if win_portable.is_none() {
                    win_portable = Some((raw_name, dl));
                }
            } else if name.ends_with(".msi") && win_msi.is_none() {
                win_msi = Some((raw_name, dl));
            }
        }
    }

    let mac_chosen = if arch == "aarch64" {
        mac_arm64.clone().or_else(|| mac_any.clone()).or_else(|| mac_x64.clone())
    } else {
        mac_x64.clone().or_else(|| mac_any.clone()).or_else(|| mac_arm64.clone())
    };
    let win_chosen = win_setup.clone().or_else(|| win_msi.clone()).or_else(|| win_portable.clone());

    let recommended = match os {
        "macos" => mac_chosen.clone(),
        "windows" => win_chosen.clone(),
        _ => None,
    };

    ReleaseInfo {
        current_version: APP_VERSION.to_string(),
        latest_tag: tag,
        latest_version: latest_version.clone(),
        update_available: is_newer_version(&latest_version, APP_VERSION),
        title,
        notes,
        html_url,
        apk_download_url: apk_url,
        windows_download_url: win_chosen.map(|(_, u)| u),
        mac_download_url: mac_chosen.map(|(_, u)| u),
        recommended_asset_name: recommended.as_ref().map(|(n, _)| n.clone()),
        recommended_download_url: recommended.map(|(_, u)| u),
    }
}

/// Downloads the platform installer directly in Rust with live progress and installs / relaunches automatically.
pub async fn download_and_install_update(
    progress: Arc<RwLock<UpdateInstallProgress>>,
    release: ReleaseInfo,
    fallback_dir: PathBuf,
) {
    let Some(url) = release.recommended_download_url.clone() else {
        let mut p = progress.write().await;
        p.phase = "error".into();
        p.error = Some(format!(
            "No desktop installer asset is attached to {} yet (CI may still be building). Try again in a moment or open GitHub Releases.",
            release.latest_tag
        ));
        return;
    };

    let filename = release
        .recommended_asset_name
        .clone()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| {
            if cfg!(target_os = "macos") {
                format!("Voxban-{}-macOS.dmg", release.latest_version)
            } else {
                format!("Voxban-Setup-{}.exe", release.latest_version)
            }
        });

    {
        let mut p = progress.write().await;
        p.phase = "downloading".into();
        p.version = release.latest_version.clone();
        p.downloaded_bytes = 0;
        p.total_bytes = 0;
        p.message = format!("Downloading {filename}…");
        p.file_path = None;
        p.error = None;
    }

    let dl_dir = dirs::download_dir().unwrap_or(fallback_dir);
    let _ = fs::create_dir_all(&dl_dir).await;
    let dest = dl_dir.join(&filename);
    let part = dl_dir.join(format!(".{filename}.part"));

    let http = match Client::builder().timeout(Duration::from_secs(300)).build() {
        Ok(c) => c,
        Err(e) => {
            set_error(&progress, format!("HTTP client error: {e}")).await;
            return;
        }
    };

    let resp = match http
        .get(&url)
        .header("User-Agent", format!("Voxban-Desktop/{APP_VERSION}"))
        .header("Accept", "application/octet-stream")
        .send()
        .await
    {
        Ok(r) if r.status().is_success() => r,
        Ok(r) => {
            set_error(&progress, format!("Download failed with HTTP {}", r.status())).await;
            return;
        }
        Err(e) => {
            set_error(&progress, format!("Network error downloading update: {e}")).await;
            return;
        }
    };

    let total = resp.content_length().unwrap_or(0);
    {
        let mut p = progress.write().await;
        p.total_bytes = total;
    }

    let mut file = match fs::File::create(&part).await {
        Ok(f) => f,
        Err(e) => {
            set_error(&progress, format!("Could not create download file: {e}")).await;
            return;
        }
    };

    let mut stream = resp.bytes_stream();
    let mut downloaded: u64 = 0;
    let mut last_tick = Instant::now();

    while let Some(chunk_res) = stream.next().await {
        match chunk_res {
            Ok(bytes) => {
                if let Err(e) = file.write_all(&bytes).await {
                    let _ = fs::remove_file(&part).await;
                    set_error(&progress, format!("Disk write error: {e}")).await;
                    return;
                }
                downloaded += bytes.len() as u64;
                if last_tick.elapsed() >= Duration::from_millis(120) {
                    last_tick = Instant::now();
                    let mut p = progress.write().await;
                    p.downloaded_bytes = downloaded;
                }
            }
            Err(e) => {
                let _ = fs::remove_file(&part).await;
                set_error(&progress, format!("Download interrupted: {e}")).await;
                return;
            }
        }
    }

    if let Err(e) = file.flush().await {
        let _ = fs::remove_file(&part).await;
        set_error(&progress, format!("Failed finalizing download: {e}")).await;
        return;
    }
    drop(file);

    if downloaded < 500_000 {
        let _ = fs::remove_file(&part).await;
        set_error(&progress, "Downloaded installer appears incomplete or invalid.".into()).await;
        return;
    }

    let _ = fs::remove_file(&dest).await;
    if let Err(e) = fs::rename(&part, &dest).await {
        if fs::copy(&part, &dest).await.is_err() {
            let _ = fs::remove_file(&part).await;
            set_error(&progress, format!("Could not save installer: {e}")).await;
            return;
        }
        let _ = fs::remove_file(&part).await;
    }

    {
        let mut p = progress.write().await;
        p.phase = "installing".into();
        p.downloaded_bytes = downloaded;
        p.total_bytes = downloaded.max(total);
        p.file_path = Some(dest.to_string_lossy().to_string());
        p.message = "Download complete! Installing update…".into();
    }

    // Apply platform-native installation
    let install_dest = dest.clone();
    let install_res = tokio::task::spawn_blocking(move || apply_downloaded_installer(&install_dest))
        .await
        .unwrap_or_else(|_| Err("Installer thread failed".into()));

    match install_res {
        Ok(InstallOutcome::Restarting(msg)) => {
            {
                let mut p = progress.write().await;
                p.phase = "restarting".into();
                p.message = msg;
            }
            // Give the frontend 900ms to render "Restarting Voxban…" before exiting so the OS installer / swap completes cleanly
            tokio::spawn(async {
                tokio::time::sleep(Duration::from_millis(900)).await;
                std::process::exit(0);
            });
        }
        Ok(InstallOutcome::OpenedManual(msg)) => {
            let mut p = progress.write().await;
            p.phase = "ready".into();
            p.message = msg;
        }
        Err(err) => {
            // Even if automatic swap failed, try opening the downloaded installer directly so the user is never stuck!
            let _ = open::that(&dest);
            let mut p = progress.write().await;
            p.phase = "ready".into();
            p.message = format!(
                "Saved installer to {} and opened it ({err}). Finish installation to launch the new version.",
                dest.to_string_lossy()
            );
        }
    }
}

async fn set_error(progress: &Arc<RwLock<UpdateInstallProgress>>, msg: String) {
    let mut p = progress.write().await;
    p.phase = "error".into();
    p.error = Some(msg.clone());
    p.message = msg;
}

enum InstallOutcome {
    Restarting(String),
    OpenedManual(String),
}

#[cfg(target_os = "macos")]
fn apply_downloaded_installer(dmg_path: &Path) -> Result<InstallOutcome, String> {
    use std::process::Command;

    // Detect if currently running from a `.app` bundle (e.g. /Applications/Voxban.app/Contents/MacOS/Voxban)
    let target_app = current_macos_app_bundle();

    if let Some(app_bundle) = target_app {
        if let Some(parent_dir) = app_bundle.parent() {
            let mnt = std::env::temp_dir().join(format!("voxban-mnt-{}", crate::models::now_ms()));
            let _ = std::fs::create_dir_all(&mnt);

            let attach = Command::new("hdiutil")
                .args([
                    "attach",
                    "-nobrowse",
                    "-readonly",
                    "-mountpoint",
                    mnt.to_str().unwrap_or("/tmp/voxban-mnt"),
                    dmg_path.to_str().unwrap_or(""),
                ])
                .output()
                .map_err(|e| format!("hdiutil attach failed: {e}"))?;

            if attach.status.success() {
                let src_app = mnt.join("Voxban.app");
                let staged_app = parent_dir.join("Voxban.app.updating");
                let _ = std::fs::remove_dir_all(&staged_app);

                if src_app.exists() {
                    let copied = Command::new("ditto")
                        .arg(&src_app)
                        .arg(&staged_app)
                        .status()
                        .map(|s| s.success())
                        .unwrap_or(false);

                    let _ = Command::new("hdiutil")
                        .args(["detach", mnt.to_str().unwrap_or(""), "-force"])
                        .status();
                    let _ = std::fs::remove_dir_all(&mnt);

                    if copied {
                        let _ = Command::new("xattr")
                            .args(["-dr", "com.apple.quarantine", staged_app.to_str().unwrap_or("")])
                            .status();

                        // Spawn detached shell script to swap the bundle cleanly right after this process exits & relaunch!
                        let pid = std::process::id();
                        let script = format!(
                            "while kill -0 {pid} 2>/dev/null; do sleep 0.2; done; rm -rf \"$1\" && mv \"$2\" \"$1\" && open -n \"$1\""
                        );
                        let spawn_ok = Command::new("/bin/sh")
                            .args([
                                "-c",
                                &script,
                                "_",
                                app_bundle.to_str().unwrap_or("/Applications/Voxban.app"),
                                staged_app.to_str().unwrap_or("/Applications/Voxban.app.updating"),
                            ])
                            .spawn()
                            .is_ok();

                        if spawn_ok {
                            return Ok(InstallOutcome::Restarting(
                                "Update installed! Restarting Voxban automatically…".into(),
                            ));
                        }
                    } else {
                        let _ = std::fs::remove_dir_all(&staged_app);
                    }
                } else {
                    let _ = Command::new("hdiutil")
                        .args(["detach", mnt.to_str().unwrap_or(""), "-force"])
                        .status();
                    let _ = std::fs::remove_dir_all(&mnt);
                }
            }
        }
    }

    // Fallback (e.g. non-writable /Applications or dev binary): mount & open DMG in Finder
    open::that(dmg_path).map_err(|e| format!("Could not open DMG: {e}"))?;
    Ok(InstallOutcome::OpenedManual(format!(
        "Downloaded {} and opened it in Finder. Drag Voxban into Applications and reopen.",
        dmg_path.file_name().and_then(|s| s.to_str()).unwrap_or("update DMG")
    )))
}

#[cfg(target_os = "macos")]
fn current_macos_app_bundle() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let mut cur = exe.as_path();
    while let Some(parent) = cur.parent() {
        if parent.extension().and_then(|s| s.to_str()) == Some("app") {
            return Some(parent.to_path_buf());
        }
        cur = parent;
    }
    None
}

#[cfg(target_os = "windows")]
fn apply_downloaded_installer(installer_path: &Path) -> Result<InstallOutcome, String> {
    use std::process::Command;

    let ext = installer_path
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_lowercase();

    let launched = if ext == "msi" {
        Command::new("msiexec")
            .args(["/i", installer_path.to_str().unwrap_or("")])
            .spawn()
            .is_ok()
    } else {
        Command::new(installer_path).spawn().is_ok() || open::that(installer_path).is_ok()
    };

    if launched {
        Ok(InstallOutcome::Restarting(
            "Installer launched! Closing Voxban so the update can complete…".into(),
        ))
    } else {
        Err("Could not launch the downloaded Windows installer.".into())
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn apply_downloaded_installer(path: &Path) -> Result<InstallOutcome, String> {
    let _ = open::that(path);
    Ok(InstallOutcome::OpenedManual(format!(
        "Downloaded update to {}",
        path.to_string_lossy()
    )))
}

pub fn is_newer_version(candidate: &str, current: &str) -> bool {
    let parse = |s: &str| -> Vec<u32> {
        s.trim()
            .trim_start_matches(['v', 'V'])
            .split('-')
            .next()
            .unwrap_or("")
            .split('.')
            .filter_map(|p| p.parse::<u32>().ok())
            .collect()
    };
    let a = parse(candidate);
    let b = parse(current);
    let len = a.len().max(b.len());
    for i in 0..len {
        let x = *a.get(i).unwrap_or(&0);
        let y = *b.get(i).unwrap_or(&0);
        if x != y {
            return x > y;
        }
    }
    false
}
