use std::path::PathBuf;
use voxban_backend::{start_server, DEFAULT_PORT};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "voxban_backend=info,tower_http=warn".into()),
        )
        .init();

    let port = std::env::var("VOXBAN_PORT")
        .ok()
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(DEFAULT_PORT);

    let frontend_dir = std::env::var("VOXBAN_FRONTEND_DIR")
        .ok()
        .map(PathBuf::from)
        .or_else(|| {
            let dev = PathBuf::from("../frontend/dist");
            if dev.is_dir() {
                Some(dev)
            } else {
                let sibling = PathBuf::from("desktop/frontend/dist");
                if sibling.is_dir() { Some(sibling) } else { None }
            }
        });

    let open_browser = std::env::var("VOXBAN_NO_OPEN").is_err();

    let (actual_port, ctx, handle) = start_server(port, None, frontend_dir)
        .await
        .expect("Failed to start Voxban backend server");

    let session = ctx.pairing_session.read().await;
    let local_url = format!("http://127.0.0.1:{actual_port}");
    println!("──────────────────────────────────────────────");
    println!("  Voxban Desktop (Rust Backend v{})", voxban_backend::models::APP_VERSION);
    println!("  Local UI:   {local_url}");
    println!("  LAN Sync:   http://{}:{actual_port}", session.host);
    println!("  Pair Code:  {}", session.code);
    println!("──────────────────────────────────────────────");
    drop(session);

    if open_browser {
        let _ = open::that(&local_url);
    }

    let _ = handle.await;
}
