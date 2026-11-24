use crate::models::{SupportTicket, APP_VERSION, SUPPORT_EMAIL};
use chrono::{DateTime, Datelike, Local};
use rand::Rng;
use serde::Serialize;

const ID_CHARS: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FormattedTicketEmail {
    pub to: String,
    pub subject: String,
    pub body: String,
    pub mailto_url: String,
    pub gmail_web_url: String,
    pub diagnostics: String,
}

pub fn generate_ticket_id() -> String {
    let now = Local::now();
    let ym = format!("{:02}{:02}", now.year() % 100, now.month());
    let mut rng = rand::thread_rng();
    let suffix: String = (0..4)
        .map(|_| ID_CHARS[rng.gen_range(0..ID_CHARS.len())] as char)
        .collect();
    format!("VB-{ym}-{suffix}")
}

pub fn desktop_diagnostics() -> String {
    let host = hostname::get()
        .ok()
        .and_then(|s| s.into_string().ok())
        .unwrap_or_else(|| "PC".to_string());
    format!(
        "- App: Voxban Desktop {APP_VERSION} (Rust backend)\n- OS: {} ({})\n- Host: {host}",
        std::env::consts::OS,
        std::env::consts::ARCH
    )
}

pub fn format_ticket_email(ticket: &SupportTicket, user_name: &str, is_follow_up: bool) -> FormattedTicketEmail {
    let diag = desktop_diagnostics();
    let prefix = if is_follow_up { "Re: " } else { "" };
    let subject = format!(
        "{prefix}[Voxban Support #{}] [{}] {}",
        ticket.id,
        ticket.category.label(),
        ticket.subject.trim()
    );
    let sender = match (user_name.trim().is_empty(), ticket.contact_email.trim().is_empty()) {
        (false, false) => format!("{} · {}", user_name.trim(), ticket.contact_email.trim()),
        (false, true) => user_name.trim().to_string(),
        (true, false) => ticket.contact_email.trim().to_string(),
        (true, true) => "Voxban PC user".to_string(),
    };
    let submitted = DateTime::from_timestamp_millis(ticket.created_at)
        .map(|dt| dt.with_timezone(&Local).format("%Y-%m-%d %H:%M").to_string())
        .unwrap_or_else(|| "now".to_string());

    let mut body = String::new();
    if is_follow_up {
        body.push_str(&format!("Follow-up on Voxban Support Ticket #{}\n\n[Write your update here]\n\n──────── Original Ticket ────────\n", ticket.id));
    } else {
        body.push_str("Voxban Support Ticket (Desktop)\n────────────────────────────────\n");
    }
    body.push_str(&format!("Ticket ID:  #{}\n", ticket.id));
    body.push_str(&format!("Category:   {}\n", ticket.category.label()));
    body.push_str(&format!("Priority:   {}\n", ticket.priority.label()));
    body.push_str(&format!("From:       {sender}\n"));
    body.push_str(&format!("Submitted:  {submitted}\n\nMessage:\n{}\n", ticket.description.trim()));
    if ticket.include_diagnostics {
        body.push_str(&format!("\nDevice & App Diagnostics:\n{diag}\n"));
    }

    let mailto_url = format!(
        "mailto:{SUPPORT_EMAIL}?subject={}&body={}",
        urlencoding::encode(&subject),
        urlencoding::encode(&body),
    );
    let gmail_web_url = format!(
        "https://mail.google.com/mail/?view=cm&fs=1&to={}&su={}&body={}",
        urlencoding::encode(SUPPORT_EMAIL),
        urlencoding::encode(&subject),
        urlencoding::encode(&body),
    );

    FormattedTicketEmail {
        to: SUPPORT_EMAIL.to_string(),
        subject,
        body,
        mailto_url,
        gmail_web_url,
        diagnostics: diag,
    }
}
