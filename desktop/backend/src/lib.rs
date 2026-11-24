pub mod ai;
pub mod biometrics;
pub mod models;
pub mod music;
pub mod routes;
pub mod storage;
pub mod support;
pub mod sync;
pub mod updater;

use routes::{build_router, AppContext};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::net::TcpListener;

pub const DEFAULT_PORT: u16 = 43821;

/// Starts the Voxban Rust backend server (LAN-reachable on 0.0.0.0:<port> for Android QR pairing)
/// and returns the actual bound port and shared AppContext.
pub async fn start_server(
    preferred_port: u16,
    data_dir: Option<PathBuf>,
    frontend_dir: Option<PathBuf>,
) -> Result<(u16, Arc<AppContext>, tokio::task::JoinHandle<()>), std::io::Error> {
    let listener = match TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], preferred_port))).await {
        Ok(l) => l,
        Err(_) => TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], 0))).await?,
    };
    let actual_port = listener.local_addr()?.port();
    let ctx = AppContext::new(actual_port, data_dir, frontend_dir);
    let app = build_router(ctx.clone());

    let handle = tokio::spawn(async move {
        if let Err(e) = axum::serve(listener, app).await {
            tracing::error!("Voxban server exited with error: {e}");
        }
    });

    Ok((actual_port, ctx, handle))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{CalendarReminder, Note, SupportCategory, SupportPriority, SupportTicket};
    use crate::support::{format_ticket_email, generate_ticket_id};
    use crate::sync::{generate_pairing_session, merge_states};
    use crate::updater::is_newer_version;

    #[test]
    fn qr_pairing_url_redirects_to_github_releases_when_app_missing() {
        let session = generate_pairing_session(43821);
        assert!(session.qr_url.starts_with("https://github.com/meyadstack/Voxban/releases/latest?voxban_pair=1&"));
        assert!(session.qr_url.contains("port=43821"));
        assert!(session.qr_url.contains(&format!("code={}", session.code)));
        assert!(session.qr_svg.contains("<svg"));
        assert_eq!(session.code.len(), 6);
    }

    #[test]
    fn bidirectional_sync_merges_notes_and_calendar_reminders() {
        let mut local = models::AppState::default();
        let mut remote = models::AppState::default();
        remote.notes.push(Note {
            id: "n-phone".into(),
            title: "Phone note".into(),
            body: "Synced from Android APK".into(),
            color: 0xFFCFE6FF,
            pinned: false,
            source: String::new(),
            created_at: 1000,
            updated_at: 2000,
        });
        remote.calendar_reminders.push(CalendarReminder {
            id: "rem-phone".into(),
            title: "Dentist".into(),
            notes: "Calendar only".into(),
            at: 3000,
            remind_before_min: 15,
            repeat: models::ReminderRepeat::Weekly,
            done: false,
            created_at: 1000,
            updated_at: 2000,
        });
        local.settings.user_name = String::new();
        remote.settings.user_name = "Abdullah".into();

        let merged = merge_states(&local, &remote);
        assert!(merged.notes.iter().any(|n| n.id == "n-phone"));
        assert!(merged.calendar_reminders.iter().any(|r| r.id == "rem-phone"));
        assert_eq!(merged.settings.user_name, "Abdullah");
    }

    #[test]
    fn support_ticket_targets_owner_gmail() {
        let id = generate_ticket_id();
        assert!(id.starts_with("VB-"));
        let ticket = SupportTicket {
            id: id.clone(),
            category: SupportCategory::Feature,
            priority: SupportPriority::Normal,
            subject: "Desktop shortcut".into(),
            description: "Works great on Windows!".into(),
            contact_email: "test@example.com".into(),
            include_diagnostics: true,
            status: models::SupportTicketStatus::Sent,
            created_at: models::now_ms(),
            updated_at: models::now_ms(),
        };
        let email = format_ticket_email(&ticket, "Alex", false);
        assert_eq!(email.to, "abdullahmeyad7@gmail.com");
        assert!(email.mailto_url.starts_with("mailto:abdullahmeyad7@gmail.com?"));
        assert!(email.gmail_web_url.contains("abdullahmeyad7%40gmail.com"));
        assert!(email.body.contains(&id));
    }

    #[test]
    fn semver_updater_compares_correctly() {
        assert!(is_newer_version("v3.5.0", "3.4.2"));
        assert!(!is_newer_version("v3.4.2", "3.4.2"));
        assert!(!is_newer_version("3.3.0", "3.4.2"));
    }

    #[test]
    fn android_kotlin_appstate_json_deserializes_and_roundtrips() {
        // Exact JSON structure produced by Android Kotlin kotlinx.serialization (Stage.color is Long!)
        let android_json = r##"{
            "workspace": {
                "boards": [{
                    "id": "b-1",
                    "title": "Product Launch",
                    "stages": [
                        { "id": "todo", "title": "To Do", "color": 4284704497, "cards": [
                            { "id": "c-1", "text": "Ship 3.4.1", "notes": null, "priority": "HIGH", "tags": ["mobile"], "createdAt": 1700000000000, "dueAt": null, "remindBeforeMin": 60 }
                        ]},
                        { "id": "done", "title": "Done", "color": "#16A34A", "cards": [] }
                    ],
                    "createdAt": 1700000000000
                }],
                "activeBoardId": "b-1"
            },
            "settings": {
                "onboarded": true,
                "userName": "Abdullah",
                "language": "en-US",
                "engine": "GEMINI",
                "themeMode": "SYSTEM",
                "cloudModels": {},
                "currency": "৳",
                "weightKg": 70.0,
                "autoCheckUpdates": true,
                "dismissedUpdateTag": "",
                "pairedDesktop": null
            },
            "chat": [],
            "sessions": [],
            "activeSessionId": null,
            "voiceChat": [],
            "notes": [{ "id": "n1", "title": "Test", "body": "Line 1\nLine 2", "color": 4293590919, "pinned": true, "source": "", "createdAt": 1, "updatedAt": 2 }],
            "transactions": [],
            "budgets": [],
            "workoutTypes": [],
            "workouts": [],
            "workoutReminder": { "enabled": false, "hour": 18, "minute": 0, "days": [1,2,3], "typeId": "walking" },
            "focusSessions": [],
            "activeFocus": null,
            "calendarReminders": [{ "id": "r1", "title": "Dentist", "notes": "", "at": 1700000000000, "remindBeforeMin": 15, "repeat": "WEEKLY", "done": false, "createdAt": 1 }],
            "savedWorkoutTracks": [],
            "downloadedWorkoutTracks": [],
            "supportTickets": []
        }"##;

        let parsed: models::AppState = serde_json::from_str(android_json).expect("Must parse Android JSON");
        assert_eq!(parsed.workspace.boards[0].stages[0].color, 4284704497);
        assert_eq!(parsed.workspace.boards[0].stages[1].color, 0xFF16A34A);
        assert_eq!(parsed.settings.user_name, "Abdullah");

        // Ensure re-serialized color is a JSON integer (compatible with Android Kotlin Stage.color: Long!)
        let reserialized = serde_json::to_value(&parsed).unwrap();
        assert!(reserialized["workspace"]["boards"][0]["stages"][0]["color"].is_u64());
    }

    #[test]
    fn encrypted_vault_roundtrip() {
        let tmp = std::env::temp_dir().join(format!("voxban-test-{}", models::new_id("v")));
        let storage = storage::Storage::new(Some(tmp.clone()));
        let entries = vec![models::PasswordEntry {
            id: "pw-1".into(),
            title: "GitHub".into(),
            username: "meyadstack".into(),
            password: "SuperSecret!123".into(),
            url: "https://github.com".into(),
            notes: String::new(),
            created_at: 1,
            updated_at: 1,
        }];
        storage.save_vault("master-pass", &entries).expect("save vault");
        assert!(storage.unlock_vault("wrong-pass").is_err());
        let decoded = storage.unlock_vault("master-pass").expect("unlock vault");
        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded[0].password, "SuperSecret!123");
        let _ = std::fs::remove_dir_all(tmp);
    }

    #[test]
    fn biometric_hardware_detection_and_secret_roundtrip() {
        let hw = biometrics::detect_hardware();
        assert!(!hw.label.is_empty());
        println!("Detected PC biometrics: available={} label={}", hw.available, hw.label);

        let tmp = std::env::temp_dir().join(format!("voxban-bio-test-{}", models::new_id("b")));
        let _ = std::fs::create_dir_all(&tmp);
        // Verify OS secure storage roundtrip on macOS Keychain / Windows DPAPI
        #[cfg(any(target_os = "macos", target_os = "windows"))]
        {
            biometrics::clear_enrolled_secret(&tmp);
            assert!(!biometrics::has_enrolled_secret(&tmp));
            if biometrics::store_enrolled_secret(&tmp, "vault-secret-342").is_ok() {
                assert!(biometrics::has_enrolled_secret(&tmp));
                let loaded = biometrics::load_enrolled_secret(&tmp).expect("load biometric secret");
                assert_eq!(loaded, "vault-secret-342");
                biometrics::clear_enrolled_secret(&tmp);
                assert!(!biometrics::has_enrolled_secret(&tmp));
            }
        }
        let _ = std::fs::remove_dir_all(tmp);
    }
}
