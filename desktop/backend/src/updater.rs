use crate::models::{APP_VERSION, GITHUB_API_LATEST_URL, GITHUB_RELEASES_URL};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::time::Duration;

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
}

pub async fn check_github_release() -> Result<ReleaseInfo, String> {
    let http = Client::builder()
        .timeout(Duration::from_secs(12))
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
    let tag = val.get("tag_name").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let latest_version = tag.trim_start_matches(['v', 'V']).to_string();
    let title = val.get("name").and_then(|v| v.as_str()).unwrap_or(&tag).to_string();
    let notes = val.get("body").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let html_url = val
        .get("html_url")
        .and_then(|v| v.as_str())
        .unwrap_or(GITHUB_RELEASES_URL)
        .to_string();

    let mut apk_url = None;
    let mut win_url = None;
    if let Some(assets) = val.get("assets").and_then(|v| v.as_array()) {
        for a in assets {
            let name = a.get("name").and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
            let dl = a.get("browser_download_url").and_then(|v| v.as_str()).map(|s| s.to_string());
            if name.ends_with(".apk") && apk_url.is_none() {
                apk_url = dl;
            } else if (name.ends_with(".exe") || name.ends_with(".msi")) && win_url.is_none() {
                win_url = dl;
            }
        }
    }

    Ok(ReleaseInfo {
        current_version: APP_VERSION.to_string(),
        latest_tag: tag,
        latest_version: latest_version.clone(),
        update_available: is_newer_version(&latest_version, APP_VERSION),
        title,
        notes,
        html_url,
        apk_download_url: apk_url,
        windows_download_url: win_url,
    })
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
