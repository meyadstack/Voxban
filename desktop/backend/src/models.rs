use chrono::Utc;
use rand::Rng;
use serde::{Deserialize, Deserializer, Serialize};
use std::collections::HashMap;

pub const APP_VERSION: &str = "3.6.0";
pub const SUPPORT_EMAIL: &str = "abdullahmeyad7@gmail.com";
pub const GITHUB_RELEASES_URL: &str = "https://github.com/meyadstack/Voxban/releases/latest";
pub const GITHUB_API_LATEST_URL: &str =
    "https://api.github.com/repos/meyadstack/Voxban/releases/latest";

/// Accepts both Android ARGB Long integers (e.g. 4284704497 / 0xFF6366F1) and CSS hex strings ("#6366F1").
pub fn deserialize_argb_color<'de, D>(deserializer: D) -> Result<u64, D::Error>
where
    D: Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum ColorVal {
        Unsigned(u64),
        Signed(i64),
        Hex(String),
    }

    match Option::<ColorVal>::deserialize(deserializer)? {
        Some(ColorVal::Unsigned(n)) => Ok(n),
        Some(ColorVal::Signed(n)) => Ok(n as u32 as u64),
        Some(ColorVal::Hex(s)) => {
            let clean = s.trim().trim_start_matches('#').trim_start_matches("0x");
            match u64::from_str_radix(clean, 16) {
                Ok(v) if clean.len() <= 6 => Ok(0xFF00_0000 | v),
                Ok(v) => Ok(v),
                Err(_) => Ok(default_stage_color()),
            }
        }
        None => Ok(default_stage_color()),
    }
}

/// Deserializes `null` as `T::default()` so nullable fields from older Android states never cause HTTP 422.
pub fn deserialize_null_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    T: Default + Deserialize<'de>,
    D: Deserializer<'de>,
{
    Ok(Option::deserialize(deserializer)?.unwrap_or_default())
}

pub fn now_ms() -> i64 {
    Utc::now().timestamp_millis()
}

pub fn new_id(prefix: &str) -> String {
    let ts = now_ms();
    let rnd: u32 = rand::thread_rng().gen_range(0..0xFFFFF);
    format!("{prefix}-{ts:x}-{rnd:x}")
}

// ---------------------------------------------------------------- Kanban Board

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Priority {
    High,
    Medium,
    Low,
}

impl Default for Priority {
    fn default() -> Self {
        Self::Medium
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Card {
    pub id: String,
    pub text: String,
    #[serde(default)]
    pub priority: Priority,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub tags: Vec<String>,
    #[serde(default)]
    pub due_at: Option<i64>,
    #[serde(default = "default_remind_min")]
    pub remind_before_min: Option<i32>,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default = "now_ms")]
    pub updated_at: i64,
}

fn default_remind_min() -> Option<i32> {
    Some(60)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Stage {
    pub id: String,
    pub title: String,
    /// ARGB color as 32-bit unsigned int (matches Android Kotlin Long, e.g. 0xFF5258D6).
    #[serde(default = "default_stage_color", deserialize_with = "deserialize_argb_color")]
    pub color: u64,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub cards: Vec<Card>,
}

fn default_stage_color() -> u64 {
    0xFF4B6BFB
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Board {
    pub id: String,
    pub title: String,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub stages: Vec<Stage>,
    #[serde(default = "now_ms")]
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Workspace {
    pub boards: Vec<Board>,
    pub active_board_id: String,
}

impl Default for Workspace {
    fn default() -> Self {
        let now = now_ms();
        let board = Board {
            id: "b-main".into(),
            title: "My Board".into(),
            created_at: now,
            stages: vec![
                Stage {
                    id: "todo".into(),
                    title: "To Do".into(),
                    color: 0xFF6366F1,
                    cards: vec![],
                },
                Stage {
                    id: "progress".into(),
                    title: "In Progress".into(),
                    color: 0xFFF59E0B,
                    cards: vec![],
                },
                Stage {
                    id: "review".into(),
                    title: "Review".into(),
                    color: 0xFF06B6D4,
                    cards: vec![],
                },
                Stage {
                    id: "done".into(),
                    title: "Done".into(),
                    color: 0xFF10B981,
                    cards: vec![],
                },
            ],
        };
        Self {
            active_board_id: board.id.clone(),
            boards: vec![board],
        }
    }
}

// ---------------------------------------------------------------- Calendar-Only Reminders

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReminderRepeat {
    None,
    Daily,
    Weekly,
    Monthly,
}

impl Default for ReminderRepeat {
    fn default() -> Self {
        Self::None
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CalendarReminder {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub notes: String,
    pub at: i64,
    #[serde(default)]
    pub remind_before_min: i32,
    #[serde(default)]
    pub repeat: ReminderRepeat,
    #[serde(default)]
    pub done: bool,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default = "now_ms")]
    pub updated_at: i64,
}

// ---------------------------------------------------------------- Notes

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Note {
    pub id: String,
    pub title: String,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub body: String,
    #[serde(default = "default_note_color", deserialize_with = "deserialize_argb_color")]
    pub color: u64,
    #[serde(default)]
    pub pinned: bool,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub source: String,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub images: Vec<String>,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default = "now_ms")]
    pub updated_at: i64,
}

fn default_note_color() -> u64 {
    0xFFEAFF87
}

// ---------------------------------------------------------------- Money

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TxType {
    Expense,
    Income,
}

impl Default for TxType {
    fn default() -> Self {
        Self::Expense
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Transaction {
    pub id: String,
    pub amount: f64,
    #[serde(rename = "type", default)]
    pub tx_type: TxType,
    #[serde(default = "default_category")]
    pub category: String,
    #[serde(default)]
    pub note: String,
    #[serde(default = "now_ms")]
    pub at: i64,
    #[serde(default = "now_ms")]
    pub created_at: i64,
}

fn default_category() -> String {
    "Other".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Budget {
    pub category: String,
    pub limit: f64,
}

// ---------------------------------------------------------------- Workouts & Music

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkoutType {
    pub id: String,
    pub name: String,
    pub emoji: String,
    #[serde(default)]
    pub gps: bool,
    #[serde(default)]
    pub steps: bool,
    #[serde(default = "default_met")]
    pub met: f64,
    #[serde(default = "default_goal_min")]
    pub goal_min: i32,
    #[serde(default)]
    pub built_in: bool,
}

fn default_met() -> f64 {
    5.0
}
fn default_goal_min() -> i32 {
    30
}

pub fn default_workout_types() -> Vec<WorkoutType> {
    vec![
        WorkoutType { id: "walking".into(), name: "Walking".into(), emoji: "🚶".into(), gps: true, steps: true, met: 3.5, goal_min: 30, built_in: true },
        WorkoutType { id: "running".into(), name: "Running".into(), emoji: "🏃".into(), gps: true, steps: true, met: 9.8, goal_min: 25, built_in: true },
        WorkoutType { id: "cycling".into(), name: "Cycling".into(), emoji: "🚴".into(), gps: true, steps: false, met: 7.5, goal_min: 40, built_in: true },
        WorkoutType { id: "yoga".into(), name: "Yoga".into(), emoji: "🧘".into(), gps: false, steps: false, met: 2.5, goal_min: 20, built_in: true },
        WorkoutType { id: "strength".into(), name: "Strength".into(), emoji: "🏋️".into(), gps: false, steps: false, met: 5.0, goal_min: 30, built_in: true },
        WorkoutType { id: "hiit".into(), name: "HIIT".into(), emoji: "🔥".into(), gps: false, steps: false, met: 8.0, goal_min: 20, built_in: true },
    ]
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkoutSession {
    pub id: String,
    pub type_id: String,
    pub name: String,
    pub emoji: String,
    pub started_at: i64,
    pub duration_sec: i64,
    #[serde(default)]
    pub distance_m: f64,
    #[serde(default)]
    pub steps: i32,
    #[serde(default)]
    pub calories: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkoutTrack {
    pub video_id: String,
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub album: String,
    #[serde(default)]
    pub duration_sec: i64,
    #[serde(default)]
    pub thumbnail_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadedTrack {
    pub track: WorkoutTrack,
    pub local_path: String,
    #[serde(default = "default_mime")]
    pub mime_type: String,
    #[serde(default)]
    pub size_bytes: u64,
    #[serde(default = "now_ms")]
    pub downloaded_at: i64,
    #[serde(default)]
    pub exported_to_music_folder: bool,
}

fn default_mime() -> String {
    "audio/webm".to_string()
}

// ---------------------------------------------------------------- Focus & Chat

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FocusSession {
    pub id: String,
    pub started_at: i64,
    pub planned_min: i32,
    pub actual_sec: i64,
    pub completed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatMessage {
    pub id: String,
    pub from_user: bool,
    pub text: String,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default)]
    pub changes: Vec<String>,
    #[serde(default)]
    pub failures: Vec<String>,
    #[serde(default)]
    pub engine: Option<String>,
    #[serde(default)]
    pub is_error: bool,
    #[serde(default)]
    pub from_voice: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatSession {
    pub id: String,
    #[serde(default = "default_chat_title")]
    pub title: String,
    #[serde(default)]
    pub messages: Vec<ChatMessage>,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default = "now_ms")]
    pub updated_at: i64,
}

fn default_chat_title() -> String {
    "New chat".to_string()
}

// ---------------------------------------------------------------- Password Vault (stored encrypted separately)

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PasswordEntry {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default = "now_ms")]
    pub updated_at: i64,
}

// ---------------------------------------------------------------- Support Tickets

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SupportCategory {
    Bug,
    Feature,
    AiVoice,
    WorkoutMusic,
    DataVault,
    Other,
}

impl Default for SupportCategory {
    fn default() -> Self {
        Self::Bug
    }
}

impl SupportCategory {
    pub fn label(&self) -> &'static str {
        match self {
            Self::Bug => "Bug report",
            Self::Feature => "Feature request",
            Self::AiVoice => "AI & Voice",
            Self::WorkoutMusic => "Workout & Music",
            Self::DataVault => "Backup & Vault",
            Self::Other => "General help",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SupportPriority {
    Low,
    Normal,
    Urgent,
}

impl Default for SupportPriority {
    fn default() -> Self {
        Self::Normal
    }
}

impl SupportPriority {
    pub fn label(&self) -> &'static str {
        match self {
            Self::Low => "Low",
            Self::Normal => "Normal",
            Self::Urgent => "Urgent",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SupportTicketStatus {
    Sent,
    Draft,
    Resolved,
}

impl Default for SupportTicketStatus {
    fn default() -> Self {
        Self::Sent
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SupportTicket {
    pub id: String,
    #[serde(default)]
    pub category: SupportCategory,
    #[serde(default)]
    pub priority: SupportPriority,
    pub subject: String,
    pub description: String,
    #[serde(default)]
    pub contact_email: String,
    #[serde(default = "default_true")]
    pub include_diagnostics: bool,
    #[serde(default)]
    pub status: SupportTicketStatus,
    #[serde(default = "now_ms")]
    pub created_at: i64,
    #[serde(default = "now_ms")]
    pub updated_at: i64,
}

fn default_true() -> bool {
    true
}

// ---------------------------------------------------------------- Settings & AppState (1:1 Android schema!)

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    #[serde(default = "default_true")]
    pub onboarded: bool,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub user_name: String,
    #[serde(default = "default_lang")]
    pub language: String,
    #[serde(default = "default_engine")]
    pub engine: String,
    #[serde(default = "default_theme")]
    pub theme_mode: String,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub cloud_models: HashMap<String, String>,
    #[serde(default = "default_gemma_model")]
    pub gemma_model_id: String,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub currency: String,
    #[serde(default = "default_weight")]
    pub weight_kg: f64,
    #[serde(default = "default_true")]
    pub auto_check_updates: bool,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub dismissed_update_tag: String,
    #[serde(default)]
    pub speak_replies: bool,
}

fn default_lang() -> String {
    "en-US".into()
}
fn default_engine() -> String {
    "GEMINI".into()
}
fn default_gemma_model() -> String {
    "gemma4:e2b".into()
}
fn default_theme() -> String {
    "SYSTEM".into()
}
fn default_weight() -> f64 {
    70.0
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            onboarded: true,
            user_name: String::new(),
            language: default_lang(),
            engine: default_engine(),
            theme_mode: default_theme(),
            cloud_models: HashMap::new(),
            gemma_model_id: default_gemma_model(),
            currency: "$".into(),
            weight_kg: 70.0,
            auto_check_updates: true,
            dismissed_update_tag: String::new(),
            speak_replies: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppState {
    #[serde(default)]
    pub workspace: Workspace,
    #[serde(default)]
    pub settings: Settings,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub sessions: Vec<ChatSession>,
    #[serde(default)]
    pub active_session_id: Option<String>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub voice_chat: Vec<ChatMessage>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub notes: Vec<Note>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub transactions: Vec<Transaction>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub budgets: Vec<Budget>,
    #[serde(default = "default_workout_types")]
    pub workout_types: Vec<WorkoutType>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub workouts: Vec<WorkoutSession>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub focus_sessions: Vec<FocusSession>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub calendar_reminders: Vec<CalendarReminder>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub saved_workout_tracks: Vec<WorkoutTrack>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub downloaded_workout_tracks: Vec<DownloadedTrack>,
    #[serde(default, deserialize_with = "deserialize_null_default")]
    pub support_tickets: Vec<SupportTicket>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            workspace: Workspace::default(),
            settings: Settings::default(),
            sessions: vec![],
            active_session_id: None,
            voice_chat: vec![],
            notes: vec![],
            transactions: vec![],
            budgets: vec![],
            workout_types: default_workout_types(),
            workouts: vec![],
            focus_sessions: vec![],
            calendar_reminders: vec![],
            saved_workout_tracks: vec![],
            downloaded_workout_tracks: vec![],
            support_tickets: vec![],
        }
    }
}
