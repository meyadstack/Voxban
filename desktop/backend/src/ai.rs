use crate::models::{
    new_id, now_ms, AppState, CalendarReminder, Card, Note, Priority, ReminderRepeat, Transaction,
    TxType,
};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AiReply {
    pub reply: String,
    pub changes: Vec<String>,
    pub engine: String,
}

/// Runs a natural-language command or chat turn against the configured AI engine
/// (Gemini / OpenAI / Claude / OpenRouter), falling back cleanly to the built-in offline parser.
pub async fn handle_assistant_message(
    prompt: &str,
    state: &mut AppState,
    keys: &HashMap<String, String>,
) -> AiReply {
    let engine = state.settings.engine.to_uppercase();
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
Respond with JSON ONLY: {{\"reply\":\"short friendly answer\",\"actions\":[...]}}. \
Supported actions:\n\
- {{\"type\":\"add_card\",\"text\":\"...\",\"priority\":\"HIGH|MEDIUM|LOW\",\"stage\":\"To Do\"}}\n\
- {{\"type\":\"add_reminder\",\"title\":\"...\",\"notes\":\"...\",\"repeat\":\"NONE|DAILY|WEEKLY|MONTHLY\"}}\n\
- {{\"type\":\"add_note\",\"title\":\"...\",\"body\":\"markdown...\"}}\n\
- {{\"type\":\"add_tx\",\"amount\":100,\"txType\":\"EXPENSE|INCOME\",\"category\":\"Food\",\"note\":\"...\"}}\n\
If the user is just chatting or asking a question, return an empty actions array.",
        stages.join(", "),
        state.notes.len(),
        state.calendar_reminders.len(),
        state.transactions.len(),
    )
}

fn parse_and_apply_json_actions(raw: &str, state: &mut AppState) -> (String, Vec<String>) {
    let cleaned = raw
        .trim()
        .trim_start_matches("```json")
        .trim_start_matches("```")
        .trim_end_matches("```")
        .trim();

    let Ok(val) = serde_json::from_str::<Value>(cleaned) else {
        return (raw.trim().to_string(), vec![]);
    };
    let reply = val
        .get("reply")
        .and_then(|v| v.as_str())
        .unwrap_or("Done.")
        .to_string();
    let mut changes = Vec::new();

    if let Some(actions) = val.get("actions").and_then(|v| v.as_array()) {
        for act in actions {
            let kind = act.get("type").and_then(|v| v.as_str()).unwrap_or("");
            match kind {
                "add_card" => {
                    if let Some(text) = act.get("text").and_then(|v| v.as_str()) {
                        let pri = match act.get("priority").and_then(|v| v.as_str()).unwrap_or("MEDIUM") {
                            "HIGH" => Priority::High,
                            "LOW" => Priority::Low,
                            _ => Priority::Medium,
                        };
                        let stage_name = act.get("stage").and_then(|v| v.as_str()).unwrap_or("To Do");
                        if let Some(board) = state
                            .workspace
                            .boards
                            .iter_mut()
                            .find(|b| b.id == state.workspace.active_board_id)
                        {
                            let idx = board
                                .stages
                                .iter()
                                .position(|s| s.title.eq_ignore_ascii_case(stage_name))
                                .unwrap_or(0);
                            if let Some(st) = board.stages.get_mut(idx) {
                                let now = now_ms();
                                st.cards.insert(
                                    0,
                                    Card {
                                        id: new_id("c"),
                                        text: text.to_string(),
                                        priority: pri,
                                        notes: None,
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
                "add_reminder" => {
                    if let Some(title) = act.get("title").and_then(|v| v.as_str()) {
                        let repeat = match act.get("repeat").and_then(|v| v.as_str()).unwrap_or("NONE") {
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
                "add_note" => {
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
                                created_at: now,
                                updated_at: now,
                            },
                        );
                        changes.push(format!("Created note “{title}”"));
                    }
                }
                "add_tx" => {
                    if let Some(amount) = act.get("amount").and_then(|v| v.as_f64()) {
                        let tx_type = if act.get("txType").and_then(|v| v.as_str()) == Some("INCOME") {
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
                _ => {}
            }
        }
    }

    (reply, changes)
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
            "Offline assistant ready! You have {total_cards} tasks, {} calendar reminders, and {} notes. Try “Add review pull request”, “Remind me to call dentist”, “I spent 25 on lunch”, or add a Gemini / OpenAI / Claude API key in Settings for full AI chat.",
            state.calendar_reminders.len(),
            state.notes.len(),
        ),
        vec![],
    )
}

async fn call_gemini(key: &str, model: &str, sys: &str, prompt: &str) -> Result<String, String> {
    let client = Client::builder().timeout(Duration::from_secs(25)).build().map_err(|e| e.to_string())?;
    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"
    );
    let resp = client
        .post(url)
        .json(&json!({
            "systemInstruction": { "parts": [{ "text": sys }] },
            "contents": [{ "role": "user", "parts": [{ "text": prompt }] }]
        }))
        .send()
        .await
        .map_err(|e| e.to_string())?;
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
