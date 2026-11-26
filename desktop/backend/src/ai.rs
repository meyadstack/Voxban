use crate::models::{
    new_id, now_ms, AppState, Board, Budget, CalendarReminder, Card, Note, Priority,
    ReminderRepeat, Stage, Transaction, TxType,
};
use futures_util::StreamExt;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;

const OLLAMA_BASE: &str = "http://127.0.0.1:11434";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AiReply {
    pub reply: String,
    pub changes: Vec<String>,
    pub engine: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct GemmaDownloadProgress {
    pub phase: String, // "idle" | "downloading" | "ready" | "error"
    pub model: String,
    pub completed_bytes: u64,
    pub total_bytes: u64,
    pub status: String,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GemmaStatus {
    pub ollama_running: bool,
    pub installed_models: Vec<String>,
    pub progress: GemmaDownloadProgress,
}

pub async fn check_gemma_status(progress: &Arc<RwLock<GemmaDownloadProgress>>) -> GemmaStatus {
    let client = Client::builder().timeout(Duration::from_secs(2)).build();
    let mut running = false;
    let mut installed = Vec::new();
    if let Ok(c) = client {
        if let Ok(resp) = c.get(format!("{OLLAMA_BASE}/api/tags")).send().await {
            if resp.status().is_success() {
                running = true;
                if let Ok(val) = resp.json::<Value>().await {
                    if let Some(arr) = val.get("models").and_then(|v| v.as_array()) {
                        for item in arr {
                            if let Some(name) = item.get("name").and_then(|v| v.as_str()) {
                                if name.to_lowercase().contains("gemma") {
                                    installed.push(name.to_string());
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    GemmaStatus {
        ollama_running: running,
        installed_models: installed,
        progress: progress.read().await.clone(),
    }
}

pub async fn download_gemma_background(
    model: String,
    progress: Arc<RwLock<GemmaDownloadProgress>>,
) {
    {
        let mut p = progress.write().await;
        p.phase = "downloading".into();
        p.model = model.clone();
        p.completed_bytes = 0;
        p.total_bytes = 0;
        p.status = format!("Starting download for {model}...");
        p.error = None;
    }

    let client = match Client::builder().connect_timeout(Duration::from_secs(10)).build() {
        Ok(c) => c,
        Err(e) => {
            let mut p = progress.write().await;
            p.phase = "error".into();
            p.error = Some(e.to_string());
            return;
        }
    };

    let resp = match client
        .post(format!("{OLLAMA_BASE}/api/pull"))
        .json(&json!({ "name": model, "stream": true }))
        .send()
        .await
    {
        Ok(r) if r.status().is_success() => r,
        Ok(r) => {
            let mut p = progress.write().await;
            p.phase = "error".into();
            p.error = Some(format!("Ollama returned HTTP {}. Make sure Ollama is up to date.", r.status()));
            return;
        }
        Err(_) => {
            let mut p = progress.write().await;
            p.phase = "error".into();
            p.error = Some("Ollama is not running locally. Install & start Ollama from ollama.com, then click Download.".into());
            return;
        }
    };

    let mut stream = resp.bytes_stream();
    let mut buf = String::new();
    while let Some(chunk) = stream.next().await {
        match chunk {
            Ok(bytes) => {
                buf.push_str(&String::from_utf8_lossy(&bytes));
                while let Some(pos) = buf.find('\n') {
                    let line = buf[..pos].trim().to_string();
                    buf.drain(..=pos);
                    if line.is_empty() {
                        continue;
                    }
                    if let Ok(ev) = serde_json::from_str::<Value>(&line) {
                        if let Some(err) = ev.get("error").and_then(|v| v.as_str()) {
                            let mut p = progress.write().await;
                            p.phase = "error".into();
                            p.error = Some(err.to_string());
                            return;
                        }
                        let status = ev.get("status").and_then(|v| v.as_str()).unwrap_or("Downloading...").to_string();
                        let completed = ev.get("completed").and_then(|v| v.as_u64()).unwrap_or(0);
                        let total = ev.get("total").and_then(|v| v.as_u64()).unwrap_or(0);
                        let mut p = progress.write().await;
                        p.status = status.clone();
                        if total > 0 {
                            p.completed_bytes = completed;
                            p.total_bytes = total;
                        }
                        if status.eq_ignore_ascii_case("success") {
                            p.phase = "ready".into();
                            p.status = format!("{model} is ready for offline AI.");
                            return;
                        }
                    }
                }
            }
            Err(e) => {
                let mut p = progress.write().await;
                p.phase = "error".into();
                p.error = Some(format!("Download stream interrupted: {e}"));
                return;
            }
        }
    }

    let mut p = progress.write().await;
    if p.phase == "downloading" {
        p.phase = "ready".into();
        p.status = format!("{model} is ready for offline AI.");
    }
}

/// Runs a natural-language command or chat turn against the configured AI engine
/// (Gemma 4 Local / Gemini / OpenAI / Claude / OpenRouter), falling back cleanly to the built-in offline parser.
pub async fn handle_assistant_message(
    prompt: &str,
    state: &mut AppState,
    keys: &HashMap<String, String>,
) -> AiReply {
    let engine = state.settings.engine.to_uppercase();

    if engine == "GEMMA4" || engine == "ON_DEVICE" {
        let model = if state.settings.gemma_model_id.trim().is_empty() {
            "gemma4:e2b".to_string()
        } else {
            state.settings.gemma_model_id.trim().to_string()
        };
        let system_prompt = build_system_prompt(state);
        match call_ollama(&model, &system_prompt, prompt).await {
            Ok(raw_reply) => {
                let (reply_text, changes) = parse_and_apply_json_actions(&raw_reply, state);
                return AiReply {
                    reply: reply_text,
                    changes,
                    engine: format!("Gemma 4 ({model})"),
                };
            }
            Err(err) => {
                let (offline_reply, changes) = apply_offline_command(prompt, state);
                if !changes.is_empty() {
                    return AiReply {
                        reply: offline_reply,
                        changes,
                        engine: "Offline (Gemma 4 unavailable)".into(),
                    };
                }
                return AiReply {
                    reply: format!("{err} Open Settings -> AI Engine to download or start Gemma 4."),
                    changes: vec![],
                    engine: "Gemma 4".into(),
                };
            }
        }
    }

    let api_key = keys.get(&engine).map(|s| s.trim()).unwrap_or("");
    if !api_key.is_empty() {
        let model = state
            .settings
            .cloud_models
            .get(&engine)
            .cloned()
            .unwrap_or_else(|| default_model(&engine).to_string());

        let system_prompt = build_system_prompt(state);
        let cloud_res = match engine.as_str() {
            "GEMINI" => call_gemini(api_key, &model, &system_prompt, prompt).await,
            "OPENAI" => call_openai_compat("https://api.openai.com/v1/chat/completions", api_key, &model, &system_prompt, prompt).await,
            "OPENROUTER" => call_openai_compat("https://openrouter.ai/api/v1/chat/completions", api_key, &model, &system_prompt, prompt).await,
            "CLAUDE" => call_claude(api_key, &model, &system_prompt, prompt).await,
            _ => Err("Unknown engine".into()),
        };

        if let Ok(raw_reply) = cloud_res {
            let (reply_text, changes) = parse_and_apply_json_actions(&raw_reply, state);
            return AiReply {
                reply: reply_text,
                changes,
                engine: format!("{engine} ({model})"),
            };
        }
    }

    // Built-in offline parser (works without any API key!)
    let (reply, changes) = apply_offline_command(prompt, state);
    AiReply {
        reply,
        changes,
        engine: "Offline".to_string(),
    }
}

pub fn default_model(engine: &str) -> &'static str {
    match engine {
        "OPENAI" => "gpt-4o-mini",
        "CLAUDE" => "claude-3-5-haiku-latest",
        "OPENROUTER" => "google/gemini-2.0-flash-exp:free",
        "GEMMA4" | "ON_DEVICE" => "gemma4:e2b",
        _ => "gemini-2.0-flash",
    }
}

fn build_system_prompt(state: &AppState) -> String {
    let board = state
        .workspace
        .boards
        .iter()
        .find(|b| b.id == state.workspace.active_board_id)
        .or_else(|| state.workspace.boards.first());
    let stages: Vec<String> = board
        .map(|b| b.stages.iter().map(|s| format!("{} ({} tasks)", s.title, s.cards.len())).collect())
        .unwrap_or_default();
    let user = if state.settings.user_name.is_empty() {
        "User".to_string()
    } else {
        state.settings.user_name.clone()
    };

    format!(
        "You are Voxban, a concise voice-first personal productivity assistant for {user}. \
Current board stages: [{}]. Open notes: {}. Calendar reminders: {}. Transactions: {}. \
Respond with JSON ONLY: {{\"reply\":\"short friendly plain-text answer\",\"actions\":[...]}}. \
Never put raw JSON, schemas, or markdown code fences inside \"reply\". \
Supported actions:\n\
- {{\"action\":\"add_card\",\"text\":\"...\",\"priority\":\"HIGH|MEDIUM|LOW\",\"stage\":\"To Do\",\"notes\":\"...\"}}\n\
- {{\"action\":\"move_card\",\"card\":\"...\",\"to\":\"Done\"}}\n\
- {{\"action\":\"update_card\",\"card\":\"...\",\"text\":\"...\",\"priority\":\"HIGH|MEDIUM|LOW\",\"notes\":\"...\",\"to\":\"...\"}}\n\
- {{\"action\":\"delete_card\",\"card\":\"...\"}}\n\
- {{\"action\":\"create_board\",\"title\":\"...\",\"stages\":[\"To Do\",\"Doing\",\"Done\"]}}\n\
- {{\"action\":\"switch_board\",\"board\":\"...\"}}\n\
- {{\"action\":\"add_stage\",\"title\":\"...\"}}\n\
- {{\"action\":\"add_reminder\",\"title\":\"...\",\"notes\":\"...\",\"repeat\":\"NONE|DAILY|WEEKLY|MONTHLY\"}}\n\
- {{\"action\":\"delete_reminder\",\"reminder\":\"...\"}}\n\
- {{\"action\":\"create_note\",\"title\":\"...\",\"body\":\"markdown...\"}}\n\
- {{\"action\":\"update_note\",\"note\":\"...\",\"title\":\"...\",\"body\":\"...\",\"append\":\"...\",\"pinned\":true}}\n\
- {{\"action\":\"delete_note\",\"note\":\"...\"}}\n\
- {{\"action\":\"add_transaction\",\"amount\":100,\"txType\":\"EXPENSE|INCOME\",\"category\":\"Food\",\"note\":\"...\"}}\n\
- {{\"action\":\"delete_transaction\",\"transaction\":\"...\"}}\n\
- {{\"action\":\"set_budget\",\"category\":\"Food\",\"limit\":500}}\n\
If the user is just chatting or asking a question, return an empty actions array.",
        stages.join(", "),
        state.notes.len(),
        state.calendar_reminders.len(),
        state.transactions.len(),
    )
}

fn strip_thinking_tags(raw: &str) -> String {
    let mut s = raw.to_string();
    for (open, close) in [
        ("<think>", "</think>"),
        ("<thought>", "</thought>"),
        ("<|thought|>", "<|/thought|>"),
    ] {
        while let Some(a) = s.to_lowercase().find(open) {
            if let Some(b_rel) = s[a..].to_lowercase().find(close) {
                let end = a + b_rel + close.len();
                s.replace_range(a..end, "");
            } else {
                break;
            }
        }
    }
    s.trim().to_string()
}

fn extract_balanced_objects(s: &str) -> Vec<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'{' {
            let mut depth = 0i32;
            let mut in_str = false;
            let mut esc = false;
            let mut j = i;
            while j < bytes.len() {
                let ch = bytes[j];
                if in_str {
                    if esc {
                        esc = false;
                    } else if ch == b'\\' {
                        esc = true;
                    } else if ch == b'"' {
                        in_str = false;
                    }
                } else if ch == b'"' {
                    in_str = true;
                } else if ch == b'{' {
                    depth += 1;
                } else if ch == b'}' {
                    depth -= 1;
                    if depth == 0 {
                        if let Some(slice) = s.get(i..=j) {
                            out.push(slice.to_string());
                        }
                        i = j;
                        break;
                    }
                }
                j += 1;
            }
        }
        i += 1;
    }
    out
}

fn is_json_schema_obj(v: &Value) -> bool {
    let Some(obj) = v.as_object() else { return false };
    let is_schema = obj.contains_key("$schema")
        || (obj.contains_key("properties") && obj.get("type").and_then(|t| t.as_str()) == Some("object"));
    is_schema && !obj.contains_key("reply") && !obj.contains_key("action")
}

fn extract_reply_regex_fallback(raw: &str) -> Option<String> {
    for key in ["\"reply\"", "\"message\"", "\"response\"", "\"answer\""] {
        if let Some(kpos) = raw.find(key) {
            let rest = &raw[kpos + key.len()..];
            if let Some(colon) = rest.find(':') {
                let after = rest[colon + 1..].trim_start();
                if let Some(inner) = after.strip_prefix('"') {
                    let mut out = String::new();
                    let mut esc = false;
                    for ch in inner.chars() {
                        if esc {
                            match ch {
                                'n' => out.push('\n'),
                                'r' => {}
                                't' => out.push('\t'),
                                '"' => out.push('"'),
                                '\\' => out.push('\\'),
                                other => out.push(other),
                            }
                            esc = false;
                        } else if ch == '\\' {
                            esc = true;
                        } else if ch == '"' {
                            break;
                        } else {
                            out.push(ch);
                        }
                    }
                    let clean = out.trim().to_string();
                    if !clean.is_empty() {
                        return Some(clean);
                    }
                }
            }
        }
    }
    None
}

pub fn sanitize_chat_reply(raw: &str) -> String {
    let mut s = strip_thinking_tags(raw);
    // Remove markdown fenced JSON blocks containing Voxban schema/action keys
    while let Some(start) = s.find("```") {
        let after_start = &s[start + 3..];
        let Some(end_rel) = after_start.find("```") else { break };
        let block = &after_start[..end_rel];
        if block.contains("\"reply\"")
            || block.contains("\"actions\"")
            || block.contains("\"action\"")
            || block.contains("\"properties\"")
            || block.contains("\"$schema\"")
        {
            s.replace_range(start..start + 3 + end_rel + 3, "");
        } else {
            break;
        }
    }
    // Strip embedded Voxban JSON envelopes or schema blobs
    for obj in extract_balanced_objects(&s) {
        if obj.contains("\"reply\"")
            || obj.contains("\"actions\"")
            || obj.contains("\"action\"")
            || (obj.contains("\"properties\"") && obj.contains("\"type\""))
            || obj.contains("\"$schema\"")
        {
            let replacement = serde_json::from_str::<Value>(&obj)
                .ok()
                .and_then(|v| {
                    if is_json_schema_obj(&v) {
                        Some(String::new())
                    } else {
                        v.get("reply")
                            .or_else(|| v.get("message"))
                            .or_else(|| v.get("response"))
                            .and_then(|x| x.as_str())
                            .map(|x| x.to_string())
                    }
                })
                .unwrap_or_default();
            s = s.replace(&obj, &replacement);
        }
    }
    let trimmed = s.trim();
    if trimmed.starts_with('{')
        && (trimmed.contains("\"properties\"")
            || trimmed.contains("\"reply\"")
            || trimmed.contains("\"actions\"")
            || trimmed.contains("\"$schema\""))
    {
        return extract_reply_regex_fallback(trimmed).unwrap_or_default();
    }
    trimmed.to_string()
}

fn unwrap_nested_reply(reply: &str) -> String {
    let mut cur = reply.trim().to_string();
    for _ in 0..3 {
        if !(cur.starts_with('{') && cur.ends_with('}')) {
            break;
        }
        let Ok(val) = serde_json::from_str::<Value>(&cur) else { break };
        if is_json_schema_obj(&val) {
            return String::new();
        }
        let Some(next) = val
            .get("reply")
            .or_else(|| val.get("message"))
            .or_else(|| val.get("response"))
            .or_else(|| val.get("text"))
            .and_then(|v| v.as_str())
        else {
            break;
        };
        cur = next.trim().to_string();
    }
    cur
}

pub fn parse_and_apply_json_actions(raw: &str, state: &mut AppState) -> (String, Vec<String>) {
    let stripped = strip_thinking_tags(raw);
    let cleaned = stripped
        .trim()
        .trim_start_matches("```json")
        .trim_start_matches("```JSON")
        .trim_start_matches("```")
        .trim_end_matches("```")
        .trim();

    let parsed_val = serde_json::from_str::<Value>(cleaned).ok().or_else(|| {
        extract_balanced_objects(cleaned)
            .into_iter()
            .find_map(|obj| serde_json::from_str::<Value>(&obj).ok())
    });

    let Some(val) = parsed_val else {
        let safe = extract_reply_regex_fallback(&stripped)
            .unwrap_or_else(|| sanitize_chat_reply(&stripped));
        return (if safe.is_empty() { "Got it!".into() } else { safe }, vec![]);
    };

    if is_json_schema_obj(&val) {
        return ("Got it! How can I help you next?".into(), vec![]);
    }

    let raw_reply = val
        .get("reply")
        .or_else(|| val.get("message"))
        .or_else(|| val.get("response"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let reply = sanitize_chat_reply(&unwrap_nested_reply(raw_reply));
    let mut changes = Vec::new();

    if let Some(actions) = val.get("actions").and_then(|v| v.as_array()) {
        for act in actions {
            let kind = act
                .get("action")
                .or_else(|| act.get("type"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_lowercase()
                .replace('-', "_");
            match kind.as_str() {
                "add" | "add_card" | "create_card" | "add_task" => {
                    if let Some(text) = act.get("text").or_else(|| act.get("title")).and_then(|v| v.as_str()) {
                        let pri = match act.get("priority").and_then(|v| v.as_str()).unwrap_or("MEDIUM").to_uppercase().as_str() {
                            "HIGH" | "URGENT" => Priority::High,
                            "LOW" => Priority::Low,
                            _ => Priority::Medium,
                        };
                        let stage_name = act.get("stage").or_else(|| act.get("column")).and_then(|v| v.as_str()).unwrap_or("To Do");
                        if let Some(board) = state
                            .workspace
                            .boards
                            .iter_mut()
                            .find(|b| b.id == state.workspace.active_board_id)
                        {
                            let idx = board
                                .stages
                                .iter()
                                .position(|s| s.title.eq_ignore_ascii_case(stage_name) || s.id.eq_ignore_ascii_case(stage_name))
                                .unwrap_or(0);
                            if let Some(st) = board.stages.get_mut(idx) {
                                let now = now_ms();
                                st.cards.insert(
                                    0,
                                    Card {
                                        id: new_id("c"),
                                        text: text.to_string(),
                                        priority: pri,
                                        notes: act.get("notes").and_then(|v| v.as_str()).map(|s| s.to_string()),
                                        tags: vec![],
                                        due_at: None,
                                        remind_before_min: Some(60),
                                        created_at: now,
                                        updated_at: now,
                                    },
                                );
                                changes.push(format!("Added task “{text}” to {}", st.title));
                            }
                        }
                    }
                }
                "move" | "move_card" | "move_task" => {
                    let card_q = act.get("card").or_else(|| act.get("text")).and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
                    let to_q = act.get("to").or_else(|| act.get("stage")).and_then(|v| v.as_str()).unwrap_or("");
                    if !card_q.is_empty() && !to_q.is_empty() {
                        if let Some(board) = state.workspace.boards.iter_mut().find(|b| b.id == state.workspace.active_board_id) {
                            let dest_idx = board.stages.iter().position(|s| s.title.eq_ignore_ascii_case(to_q) || s.id.eq_ignore_ascii_case(to_q));
                            if let Some(di) = dest_idx {
                                let mut moved: Option<Card> = None;
                                for st in &mut board.stages {
                                    if let Some(ci) = st.cards.iter().position(|c| c.text.to_lowercase().contains(&card_q)) {
                                        moved = Some(st.cards.remove(ci));
                                        break;
                                    }
                                }
                                if let Some(mut c) = moved {
                                    c.updated_at = now_ms();
                                    let title = c.text.clone();
                                    let dest_title = board.stages[di].title.clone();
                                    board.stages[di].cards.insert(0, c);
                                    changes.push(format!("Moved “{title}” to {dest_title}"));
                                }
                            }
                        }
                    }
                }
                "update" | "update_card" => {
                    let card_q = act.get("card").and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
                    if !card_q.is_empty() {
                        if let Some(board) = state.workspace.boards.iter_mut().find(|b| b.id == state.workspace.active_board_id) {
                            for st in &mut board.stages {
                                if let Some(c) = st.cards.iter_mut().find(|c| c.text.to_lowercase().contains(&card_q)) {
                                    if let Some(nt) = act.get("text").or_else(|| act.get("title")).and_then(|v| v.as_str()) {
                                        if !nt.trim().is_empty() { c.text = nt.trim().to_string(); }
                                    }
                                    if let Some(nn) = act.get("notes").and_then(|v| v.as_str()) {
                                        c.notes = Some(nn.to_string());
                                    }
                                    if let Some(pr) = act.get("priority").and_then(|v| v.as_str()) {
                                        c.priority = match pr.to_uppercase().as_str() {
                                            "HIGH" | "URGENT" => Priority::High,
                                            "LOW" => Priority::Low,
                                            _ => Priority::Medium,
                                        };
                                    }
                                    c.updated_at = now_ms();
                                    changes.push(format!("Updated “{}”", c.text));
                                    break;
                                }
                            }
                        }
                    }
                }
                "delete" | "delete_card" | "remove_card" => {
                    let card_q = act.get("card").or_else(|| act.get("text")).and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
                    if !card_q.is_empty() {
                        if let Some(board) = state.workspace.boards.iter_mut().find(|b| b.id == state.workspace.active_board_id) {
                            for st in &mut board.stages {
                                if let Some(ci) = st.cards.iter().position(|c| c.text.to_lowercase().contains(&card_q)) {
                                    let removed = st.cards.remove(ci);
                                    changes.push(format!("Deleted “{}”", removed.text));
                                    break;
                                }
                            }
                        }
                    }
                }
                "create_board" => {
                    if let Some(title) = act.get("title").and_then(|v| v.as_str()) {
                        let colors = [0xFF6366F1u64, 0xFFF59E0B, 0xFF06B6D4, 0xFF10B981, 0xFFEC4899];
                        let stages: Vec<Stage> = act
                            .get("stages")
                            .and_then(|v| v.as_array())
                            .map(|arr| {
                                arr.iter()
                                    .enumerate()
                                    .filter_map(|(i, s)| {
                                        let t = s.as_str().or_else(|| s.get("title").and_then(|x| x.as_str()))?;
                                        Some(Stage { id: new_id("s"), title: t.to_string(), color: colors[i % colors.len()], cards: vec![] })
                                    })
                                    .collect::<Vec<_>>()
                            })
                            .filter(|v| !v.is_empty())
                            .unwrap_or_else(|| vec![
                                Stage { id: "todo".into(), title: "To Do".into(), color: 0xFF6366F1, cards: vec![] },
                                Stage { id: "progress".into(), title: "In Progress".into(), color: 0xFFF59E0B, cards: vec![] },
                                Stage { id: "done".into(), title: "Done".into(), color: 0xFF10B981, cards: vec![] },
                            ]);
                        let id = new_id("b");
                        state.workspace.boards.push(Board { id: id.clone(), title: title.to_string(), stages, created_at: now_ms() });
                        state.workspace.active_board_id = id;
                        changes.push(format!("Created board “{title}”"));
                    }
                }
                "switch_board" => {
                    if let Some(q) = act.get("board").or_else(|| act.get("title")).and_then(|v| v.as_str()) {
                        let ql = q.to_lowercase();
                        if let Some(b) = state.workspace.boards.iter().find(|b| b.title.to_lowercase().contains(&ql)) {
                            state.workspace.active_board_id = b.id.clone();
                            changes.push(format!("Switched to board “{}”", b.title));
                        }
                    }
                }
                "add_stage" => {
                    if let Some(title) = act.get("title").and_then(|v| v.as_str()) {
                        if let Some(b) = state.workspace.boards.iter_mut().find(|b| b.id == state.workspace.active_board_id) {
                            b.stages.push(Stage { id: new_id("s"), title: title.to_string(), color: 0xFF6366F1, cards: vec![] });
                            changes.push(format!("Added stage “{title}”"));
                        }
                    }
                }
                "add_reminder" | "create_reminder" => {
                    if let Some(title) = act.get("title").and_then(|v| v.as_str()) {
                        let repeat = match act.get("repeat").and_then(|v| v.as_str()).unwrap_or("NONE").to_uppercase().as_str() {
                            "DAILY" => ReminderRepeat::Daily,
                            "WEEKLY" => ReminderRepeat::Weekly,
                            "MONTHLY" => ReminderRepeat::Monthly,
                            _ => ReminderRepeat::None,
                        };
                        let now = now_ms();
                        state.calendar_reminders.push(CalendarReminder {
                            id: new_id("rem"),
                            title: title.to_string(),
                            notes: act.get("notes").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                            at: now + 3_600_000,
                            remind_before_min: 15,
                            repeat,
                            done: false,
                            created_at: now,
                            updated_at: now,
                        });
                        changes.push(format!("Added calendar reminder “{title}”"));
                    }
                }
                "delete_reminder" => {
                    if let Some(q) = act.get("reminder").or_else(|| act.get("title")).and_then(|v| v.as_str()) {
                        let ql = q.to_lowercase();
                        if let Some(pos) = state.calendar_reminders.iter().position(|r| r.title.to_lowercase().contains(&ql)) {
                            let rem = state.calendar_reminders.remove(pos);
                            changes.push(format!("Deleted reminder “{}”", rem.title));
                        }
                    }
                }
                "add_note" | "create_note" => {
                    if let Some(title) = act.get("title").and_then(|v| v.as_str()) {
                        let body = act.get("body").and_then(|v| v.as_str()).unwrap_or("").to_string();
                        let now = now_ms();
                        state.notes.insert(
                            0,
                            Note {
                                id: new_id("n"),
                                title: title.to_string(),
                                body,
                                color: 0xFFEAFF87,
                                pinned: false,
                                source: String::new(),
                                images: vec![],
                                created_at: now,
                                updated_at: now,
                            },
                        );
                        changes.push(format!("Created note “{title}”"));
                    }
                }
                "update_note" | "edit_note" => {
                    let q = act.get("note").or_else(|| act.get("title")).and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
                    if !q.is_empty() {
                        if let Some(n) = state.notes.iter_mut().find(|n| n.title.to_lowercase().contains(&q)) {
                            if let Some(t) = act.get("title").and_then(|v| v.as_str()) {
                                if act.get("note").is_some() && !t.trim().is_empty() { n.title = t.trim().to_string(); }
                            }
                            if let Some(b) = act.get("body").and_then(|v| v.as_str()) {
                                n.body = b.to_string();
                            } else if let Some(app) = act.get("append").and_then(|v| v.as_str()) {
                                n.body = format!("{}\n{}", n.body.trim_end(), app.trim()).trim().to_string();
                            }
                            if let Some(pin) = act.get("pinned").and_then(|v| v.as_bool()) {
                                n.pinned = pin;
                            }
                            n.updated_at = now_ms();
                            changes.push(format!("Updated note “{}”", n.title));
                        }
                    }
                }
                "delete_note" => {
                    let q = act.get("note").or_else(|| act.get("title")).and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
                    if !q.is_empty() {
                        if let Some(pos) = state.notes.iter().position(|n| n.title.to_lowercase().contains(&q)) {
                            let rem = state.notes.remove(pos);
                            changes.push(format!("Deleted note “{}”", rem.title));
                        }
                    }
                }
                "add_tx" | "add_transaction" | "add_expense" | "add_income" => {
                    if let Some(amount) = act.get("amount").and_then(|v| v.as_f64()) {
                        let tstr = act.get("txType").or_else(|| act.get("type")).and_then(|v| v.as_str()).unwrap_or("");
                        let tx_type = if tstr.eq_ignore_ascii_case("INCOME") || kind == "add_income" {
                            TxType::Income
                        } else {
                            TxType::Expense
                        };
                        let cat = act.get("category").and_then(|v| v.as_str()).unwrap_or("Other").to_string();
                        let note = act.get("note").and_then(|v| v.as_str()).unwrap_or("").to_string();
                        let now = now_ms();
                        state.transactions.insert(
                            0,
                            Transaction {
                                id: new_id("tx"),
                                amount,
                                tx_type: tx_type.clone(),
                                category: cat.clone(),
                                note,
                                at: now,
                                created_at: now,
                            },
                        );
                        changes.push(format!(
                            "Logged {} {:.2} ({})",
                            if tx_type == TxType::Income { "income" } else { "expense" },
                            amount,
                            cat
                        ));
                    }
                }
                "delete_transaction" => {
                    let q = act.get("transaction").or_else(|| act.get("id")).and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
                    if !q.is_empty() {
                        if let Some(pos) = state.transactions.iter().position(|t| {
                            t.id.to_lowercase() == q || t.note.to_lowercase().contains(&q) || t.category.to_lowercase().contains(&q)
                        }) {
                            let rem = state.transactions.remove(pos);
                            changes.push(format!("Deleted {} ({:.2})", rem.category, rem.amount));
                        }
                    }
                }
                "set_budget" => {
                    if let Some(cat) = act.get("category").and_then(|v| v.as_str()) {
                        let limit = act.get("limit").and_then(|v| v.as_f64()).unwrap_or(0.0);
                        state.budgets.retain(|b| !b.category.eq_ignore_ascii_case(cat));
                        if limit > 0.0 {
                            state.budgets.push(Budget { category: cat.to_string(), limit });
                            changes.push(format!("Set {cat} budget to {limit:.0}"));
                        } else {
                            changes.push(format!("Removed {cat} budget"));
                        }
                    }
                }
                _ => {}
            }
        }
    }

    let final_reply = if reply.is_empty() {
        if changes.is_empty() {
            "Done.".to_string()
        } else {
            format!("{}.", changes.join(". "))
        }
    } else {
        reply
    };

    (final_reply, changes)
}

fn apply_offline_command(prompt: &str, state: &mut AppState) -> (String, Vec<String>) {
    let raw = prompt.trim();
    let lower = raw.to_lowercase();
    let now = now_ms();

    // 1. Calendar-only reminder
    if lower.starts_with("remind me") || lower.starts_with("reminder:") || lower.starts_with("add reminder") {
        let title = raw
            .trim_start_matches(|c: char| !c.is_alphabetic())
            .split_once("to ")
            .map(|(_, r)| r)
            .or_else(|| raw.split_once(':').map(|(_, r)| r))
            .unwrap_or(raw)
            .trim()
            .to_string();
        let clean = if title.is_empty() { raw.to_string() } else { title };
        state.calendar_reminders.push(CalendarReminder {
            id: new_id("rem"),
            title: clean.clone(),
            notes: String::new(),
            at: now + 3_600_000,
            remind_before_min: 15,
            repeat: ReminderRepeat::None,
            done: false,
            created_at: now,
            updated_at: now,
        });
        return (
            format!("Saved calendar reminder “{clean}” (visible in Calendar only, not on your board)."),
            vec![format!("Added calendar reminder “{clean}”")],
        );
    }

    // 2. Expense / income
    if lower.contains("spent") || lower.contains("earned") || lower.contains("salary") || lower.contains("income") {
        let is_income = lower.contains("earned") || lower.contains("salary") || lower.contains("income");
        let num = raw
            .split_whitespace()
            .find_map(|tok| tok.trim_matches(|c: char| !c.is_ascii_digit() && c != '.').parse::<f64>().ok());
        if let Some(amount) = num {
            let cat = if is_income { "Salary" } else if lower.contains("food") || lower.contains("lunch") { "Food" } else if lower.contains("grocer") { "Groceries" } else { "Other" };
            state.transactions.insert(
                0,
                Transaction {
                    id: new_id("tx"),
                    amount,
                    tx_type: if is_income { TxType::Income } else { TxType::Expense },
                    category: cat.to_string(),
                    note: raw.to_string(),
                    at: now,
                    created_at: now,
                },
            );
            let sym = if state.settings.currency.is_empty() { "$" } else { &state.settings.currency };
            return (
                format!("Logged {sym}{amount:.2} under {cat}."),
                vec![format!("Logged {sym}{amount:.2} ({cat})")],
            );
        }
    }

    // 3. Note
    if lower.starts_with("note:") || lower.starts_with("make a note") || lower.starts_with("write a note") {
        let content = raw
            .split_once(':')
            .map(|(_, r)| r.trim())
            .unwrap_or(raw)
            .to_string();
        let title = content.split(['.', ',', '\n']).next().unwrap_or("Quick Note").trim().chars().take(48).collect::<String>();
        state.notes.insert(
            0,
            Note {
                id: new_id("n"),
                title: if title.is_empty() { "Quick Note".into() } else { title.clone() },
                body: content.clone(),
                color: 0xFFC8F2DC,
                pinned: false,
                source: raw.to_string(),
                images: vec![],
                created_at: now,
                updated_at: now,
            },
        );
        return (
            format!("Created note “{title}”."),
            vec![format!("Created note “{title}”")],
        );
    }

    // 4. Add task
    if lower.starts_with("add ") || lower.starts_with("create task") || lower.starts_with("todo ") {
        let task = raw
            .strip_prefix("add ")
            .or_else(|| raw.strip_prefix("Add "))
            .or_else(|| raw.strip_prefix("todo "))
            .unwrap_or(raw)
            .trim()
            .to_string();
        let pri = if lower.contains("high priority") || lower.contains("urgent") {
            Priority::High
        } else if lower.contains("low priority") {
            Priority::Low
        } else {
            Priority::Medium
        };
        if let Some(board) = state
            .workspace
            .boards
            .iter_mut()
            .find(|b| b.id == state.workspace.active_board_id)
        {
            if let Some(first) = board.stages.first_mut() {
                first.cards.insert(
                    0,
                    Card {
                        id: new_id("c"),
                        text: task.clone(),
                        priority: pri,
                        notes: None,
                        tags: vec![],
                        due_at: None,
                        remind_before_min: Some(60),
                        created_at: now,
                        updated_at: now,
                    },
                );
                return (
                    format!("Added “{task}” to {}.", first.title),
                    vec![format!("Added task “{task}”")],
                );
            }
        }
    }

    // 5. Summary / help
    let total_cards: usize = state
        .workspace
        .boards
        .iter()
        .find(|b| b.id == state.workspace.active_board_id)
        .map(|b| b.stages.iter().map(|s| s.cards.len()).sum())
        .unwrap_or(0);
    (
        format!(
            "Offline assistant ready! You have {total_cards} tasks, {} calendar reminders, and {} notes. Try “Add review pull request”, “Remind me to call dentist”, “I spent 25 on lunch”, or enable Gemma 4 / add an API key in Settings.",
            state.calendar_reminders.len(),
            state.notes.len(),
        ),
        vec![],
    )
}

async fn call_ollama(model: &str, sys: &str, prompt: &str) -> Result<String, String> {
    let client = Client::builder().timeout(Duration::from_secs(90)).build().map_err(|e| e.to_string())?;
    let resp = client
        .post(format!("{OLLAMA_BASE}/api/chat"))
        .json(&json!({
            "model": model,
            "stream": false,
            "format": "json",
            "options": { "temperature": 0.2 },
            "messages": [
                { "role": "system", "content": sys },
                { "role": "user", "content": prompt }
            ]
        }))
        .send()
        .await
        .map_err(|_| "Could not reach local Gemma 4 (Ollama on port 11434).".to_string())?;
    if !resp.status().is_success() {
        return Err(format!("Local Gemma 4 returned HTTP {}. Download {model} in Settings first.", resp.status()));
    }
    let val: Value = resp.json().await.map_err(|e| e.to_string())?;
    val.pointer("/message/content")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| "Empty Gemma 4 reply".to_string())
}

async fn call_gemini(key: &str, model: &str, sys: &str, prompt: &str) -> Result<String, String> {
    let client = Client::builder().timeout(Duration::from_secs(25)).build().map_err(|e| e.to_string())?;
    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"
    );
    let is_gemma = model.to_lowercase().starts_with("gemma");
    let body = if is_gemma {
        json!({
            "contents": [{ "role": "user", "parts": [{ "text": format!("{sys}\n\nUser request:\n{prompt}") }] }]
        })
    } else {
        json!({
            "systemInstruction": { "parts": [{ "text": sys }] },
            "contents": [{ "role": "user", "parts": [{ "text": prompt }] }],
            "generationConfig": { "responseMimeType": "application/json", "temperature": 0.2 }
        })
    };
    let resp = client.post(url).json(&body).send().await.map_err(|e| e.to_string())?;
    let val: Value = resp.json().await.map_err(|e| e.to_string())?;
    val.pointer("/candidates/0/content/parts/0/text")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| "Empty Gemini reply".to_string())
}

async fn call_openai_compat(url: &str, key: &str, model: &str, sys: &str, prompt: &str) -> Result<String, String> {
    let client = Client::builder().timeout(Duration::from_secs(25)).build().map_err(|e| e.to_string())?;
    let resp = client
        .post(url)
        .bearer_auth(key)
        .json(&json!({
            "model": model,
            "response_format": { "type": "json_object" },
            "messages": [
                { "role": "system", "content": sys },
                { "role": "user", "content": prompt }
            ]
        }))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let val: Value = resp.json().await.map_err(|e| e.to_string())?;
    val.pointer("/choices/0/message/content")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| "Empty AI reply".to_string())
}

async fn call_claude(key: &str, model: &str, sys: &str, prompt: &str) -> Result<String, String> {
    let client = Client::builder().timeout(Duration::from_secs(25)).build().map_err(|e| e.to_string())?;
    let resp = client
        .post("https://api.anthropic.com/v1/messages")
        .header("x-api-key", key)
        .header("anthropic-version", "2023-06-01")
        .json(&json!({
            "model": model,
            "max_tokens": 1024,
            "system": sys,
            "messages": [{ "role": "user", "content": prompt }]
        }))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let val: Value = resp.json().await.map_err(|e| e.to_string())?;
    val.pointer("/content/0/text")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| "Empty Claude reply".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn never_leaks_json_schema_in_chat_replies() {
        let mut st = AppState::default();
        let schema_echo = r#"{"type":"object","properties":{"reply":{"type":"string"},"actions":{"type":"array"}}}"#;
        let (r1, c1) = parse_and_apply_json_actions(schema_echo, &mut st);
        assert!(!r1.contains("properties"));
        assert!(c1.is_empty());

        let nested = r#"{"reply":"{\"reply\":\"All set!\",\"actions\":[]}","actions":[]}"#;
        let (r2, _) = parse_and_apply_json_actions(nested, &mut st);
        assert_eq!(r2, "All set!");

        let truncated = r#"{"reply": "Added your grocery expense of $45", "actions": [{"action":"add_tx""#;
        let (r3, _) = parse_and_apply_json_actions(truncated, &mut st);
        assert_eq!(r3, "Added your grocery expense of $45");

        let fenced_plus_prose = "Sure!\n```json\n{\"reply\":\"Added milk to your tasks.\",\"actions\":[{\"action\":\"add_card\",\"text\":\"Buy milk\"}]}\n```";
        let (r4, c4) = parse_and_apply_json_actions(fenced_plus_prose, &mut st);
        assert_eq!(r4, "Added milk to your tasks.");
        assert_eq!(c4.len(), 1);
    }
}
