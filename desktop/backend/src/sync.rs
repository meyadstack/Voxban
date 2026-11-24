use crate::models::{AppState, Board, CalendarReminder, FocusSession, Note, SupportTicket, Transaction, WorkoutSession, WorkoutTrack, GITHUB_RELEASES_URL};
use qrcode::{render::svg, QrCode};
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::UdpSocket;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingSessionInfo {
    pub host: String,
    pub port: u16,
    pub token: String,
    pub code: String,
    pub pc_name: String,
    pub qr_url: String,
    pub qr_svg: String,
    pub github_releases_url: String,
}

pub fn generate_pairing_session(port: u16) -> PairingSessionInfo {
    let host = detect_lan_ip();
    let pc_name = hostname::get()
        .ok()
        .and_then(|s| s.into_string().ok())
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| "Voxban Windows PC".to_string());

    let mut rng = rand::thread_rng();
    let token: String = (0..24)
        .map(|_| {
            const CHARS: &[u8] = b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
            CHARS[rng.gen_range(0..CHARS.len())] as char
        })
        .collect();
    let code = format!("{:06}", rng.gen_range(100_000..999_999));

    // Smart QR URL:
    // - Scanning with the Voxban Android QR Scanner parses host/port/token/code and links instantly over Wi-Fi.
    // - Scanning with a standard phone camera (when Voxban Android is NOT installed) opens GitHub Releases directly to download the APK!
    let qr_url = format!(
        "{}?voxban_pair=1&host={}&port={}&token={}&code={}&name={}",
        GITHUB_RELEASES_URL,
        urlencoding::encode(&host),
        port,
        urlencoding::encode(&token),
        urlencoding::encode(&code),
        urlencoding::encode(&pc_name),
    );

    let qr_svg = render_qr_svg(&qr_url);

    PairingSessionInfo {
        host,
        port,
        token,
        code,
        pc_name,
        qr_url,
        qr_svg,
        github_releases_url: GITHUB_RELEASES_URL.to_string(),
    }
}

pub fn render_qr_svg(data: &str) -> String {
    match QrCode::new(data.as_bytes()) {
        Ok(code) => code
            .render::<svg::Color>()
            .min_dimensions(240, 240)
            .dark_color(svg::Color("#1B201C"))
            .light_color(svg::Color("#FFFFFF"))
            .quiet_zone(true)
            .build(),
        Err(_) => String::new(),
    }
}

pub fn detect_lan_ip() -> String {
    // Non-blocking UDP route probe discovers the primary Wi-Fi / Ethernet LAN IPv4 address without sending packets.
    if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
        if sock.connect("8.8.8.8:80").is_ok() {
            if let Ok(addr) = sock.local_addr() {
                let ip = addr.ip().to_string();
                if !ip.starts_with("127.") && !ip.contains(':') {
                    return ip;
                }
            }
        }
    }
    "127.0.0.1".to_string()
}

/// Bidirectionally merges local PC state and remote Android state so neither device loses data.
pub fn merge_states(local: &AppState, remote: &AppState) -> AppState {
    let mut merged = local.clone();

    // Workspace boards: merge by board ID, preferring remote cards when newer
    merged.workspace.boards = merge_boards(&local.workspace.boards, &remote.workspace.boards);
    if !merged.workspace.boards.iter().any(|b| b.id == merged.workspace.active_board_id) {
        if let Some(first) = merged.workspace.boards.first() {
            merged.workspace.active_board_id = first.id.clone();
        }
    }

    // Notes: merge by id keeping latest updatedAt
    merged.notes = merge_by_id(&local.notes, &remote.notes, |n| n.id.clone(), |n| n.updated_at);
    merged.notes.sort_by(|a, b| b.pinned.cmp(&a.pinned).then(b.updated_at.cmp(&a.updated_at)));

    // Calendar-only reminders: merge by id keeping latest updatedAt
    merged.calendar_reminders = merge_by_id(
        &local.calendar_reminders,
        &remote.calendar_reminders,
        |r| r.id.clone(),
        |r| r.updated_at,
    );
    merged.calendar_reminders.sort_by_key(|r| r.at);

    // Transactions: merge by id
    merged.transactions = merge_by_id(
        &local.transactions,
        &remote.transactions,
        |t| t.id.clone(),
        |t| t.created_at,
    );
    merged.transactions.sort_by(|a, b| b.at.cmp(&a.at));

    // Budgets: merge by lowercase category
    let mut budget_map: HashMap<String, crate::models::Budget> = HashMap::new();
    for b in &local.budgets {
        budget_map.insert(b.category.to_lowercase(), b.clone());
    }
    for b in &remote.budgets {
        budget_map.insert(b.category.to_lowercase(), b.clone());
    }
    merged.budgets = budget_map.into_values().collect();

    // Workouts & focus sessions
    merged.workouts = merge_by_id(&local.workouts, &remote.workouts, |w| w.id.clone(), |w| w.started_at);
    merged.workouts.sort_by(|a, b| b.started_at.cmp(&a.started_at));

    merged.focus_sessions = merge_by_id(&local.focus_sessions, &remote.focus_sessions, |f| f.id.clone(), |f| f.started_at);

    // Saved workout tracks: merge by videoId
    let mut track_map: HashMap<String, WorkoutTrack> = HashMap::new();
    for t in &local.saved_workout_tracks {
        track_map.insert(t.video_id.clone(), t.clone());
    }
    for t in &remote.saved_workout_tracks {
        track_map.entry(t.video_id.clone()).or_insert_with(|| t.clone());
    }
    merged.saved_workout_tracks = track_map.into_values().collect();

    // Support tickets: merge by id keeping latest updatedAt
    merged.support_tickets = merge_by_id(
        &local.support_tickets,
        &remote.support_tickets,
        |t| t.id.clone(),
        |t| t.updated_at,
    );
    merged.support_tickets.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));

    // Adopt userName / currency if PC doesn't have one set yet
    if merged.settings.user_name.trim().is_empty() && !remote.settings.user_name.trim().is_empty() {
        merged.settings.user_name = remote.settings.user_name.clone();
    }
    if merged.settings.currency.trim().is_empty() && !remote.settings.currency.trim().is_empty() {
        merged.settings.currency = remote.settings.currency.clone();
    }

    // Keep PC's own downloaded track paths untouched
    merged.downloaded_workout_tracks = local.downloaded_workout_tracks.clone();

    merged
}

fn merge_boards(local: &[Board], remote: &[Board]) -> Vec<Board> {
    if remote.is_empty() {
        return local.to_vec();
    }
    let mut map: HashMap<String, Board> = HashMap::new();
    for b in local {
        map.insert(b.id.clone(), b.clone());
    }
    for rb in remote {
        if let Some(lb) = map.get_mut(&rb.id) {
            // Merge cards within matching stages by card id + updatedAt
            let mut stage_map: HashMap<String, usize> = HashMap::new();
            for (idx, s) in lb.stages.iter().enumerate() {
                stage_map.insert(s.id.clone(), idx);
            }
            for rs in &rb.stages {
                if let Some(&idx) = stage_map.get(&rs.id) {
                    lb.stages[idx].cards = merge_by_id(
                        &lb.stages[idx].cards,
                        &rs.cards,
                        |c| c.id.clone(),
                        |c| c.updated_at,
                    );
                } else {
                    lb.stages.push(rs.clone());
                }
            }
        } else {
            map.insert(rb.id.clone(), rb.clone());
        }
    }
    let mut out: Vec<Board> = map.into_values().collect();
    out.sort_by_key(|b| b.created_at);
    out
}

fn merge_by_id<T: Clone>(
    local: &[T],
    remote: &[T],
    id_fn: impl Fn(&T) -> String,
    ts_fn: impl Fn(&T) -> i64,
) -> Vec<T> {
    let mut map: HashMap<String, T> = HashMap::new();
    for item in local {
        map.insert(id_fn(item), item.clone());
    }
    for item in remote {
        let id = id_fn(item);
        match map.get(&id) {
            Some(existing) if ts_fn(existing) > ts_fn(item) => {}
            _ => {
                map.insert(id, item.clone());
            }
        }
    }
    map.into_values().collect()
}

// Keep compiler happy if helper types are used elsewhere
#[allow(dead_code)]
fn _type_check(_: (&CalendarReminder, &Note, &Transaction, &WorkoutSession, &FocusSession, &SupportTicket)) {}
