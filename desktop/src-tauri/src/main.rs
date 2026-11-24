#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::{WebviewUrl, WebviewWindowBuilder};
use voxban_backend::{start_server, DEFAULT_PORT};

fn main() {
    // Launch the embedded Rust backend (API + LAN QR Phone Sync + Music Cache/Downloader + Embedded React UI)
    let rt = tokio::runtime::Runtime::new().expect("Failed to create Tokio runtime");
    let (port, _ctx, _handle) = rt
        .block_on(start_server(DEFAULT_PORT, None, None))
        .expect("Failed to start embedded Voxban Rust server");

    let app_url: tauri::Url = format!("http://127.0.0.1:{port}")
        .parse()
        .expect("Valid localhost URL");

    tauri::Builder::default()
        .setup(move |app| {
            WebviewWindowBuilder::new(app, "main", WebviewUrl::External(app_url))
                .title("Voxban — Windows PC")
                .inner_size(1240.0, 820.0)
                .min_inner_size(860.0, 620.0)
                .center()
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running Voxban Desktop");
}
