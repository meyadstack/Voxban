import { CheckCircle2, Download, ExternalLink, Laptop, QrCode, RefreshCw, Smartphone, Unlink, Wifi } from "lucide-react";
import { api } from "../api";
import type { PairInfoResponse } from "../types";
import { VoxbanAvatar } from "./VoxbanAvatar";

interface Props {
  pairInfo: PairInfoResponse | null;
  onRefreshQr: () => void;
  onUnlink: () => void;
  onContinueStandalone: () => void;
  isModal?: boolean;
  onClose?: () => void;
}

/**
 * Windows PC Phone QR Scan Gate & Pairing View:
 * - Displays live QR code scanned by the Voxban Android APK to link & sync over Wi-Fi.
 * - If the user doesn't have the phone app installed, scanning with a regular phone camera
 *   OR clicking the Download button sends them straight to GitHub Releases to download the APK!
 */
export function PhonePairGate({
  pairInfo,
  onRefreshQr,
  onUnlink,
  onContinueStandalone,
  isModal = false,
  onClose,
}: Props) {
  const session = pairInfo?.session;
  const phone = pairInfo?.pairedPhone;
  const githubUrl = pairInfo?.githubReleasesUrl || "https://github.com/meyadstack/Voxban/releases/latest";
  const isMac = typeof navigator !== "undefined" && /Mac|Darwin/i.test(navigator.platform || navigator.userAgent);
  const platformTitle = isMac ? "Voxban for macOS" : "Voxban for Windows PC";
  const deviceWord = isMac ? "Mac" : "Windows PC";

  return (
    <div
      className={
        isModal
          ? "fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm overflow-y-auto"
          : "min-h-screen w-full flex items-center justify-center p-4 sm:p-8"
      }
      style={{ background: isModal ? undefined : "var(--vk-bg)" }}
    >
      <div className="vk-card w-full max-w-4xl p-6 sm:p-8 shadow-xl relative">
        {isModal && onClose && (
          <button
            onClick={onClose}
            className="vk-btn-outline px-3 py-1.5 text-xs absolute top-5 right-5 cursor-pointer"
          >
            Close
          </button>
        )}

        {/* Header */}
        <div className="flex items-center gap-3 mb-6">
          <VoxbanAvatar size={48} />
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <h1 className="text-2xl font-bold tracking-tight">{platformTitle}</h1>
              <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-[#EAFF87] text-[#1B201C]">
                Rust Backend · Synced with APK
              </span>
            </div>
            <p className="text-sm" style={{ color: "var(--vk-ink-2)" }}>
              Scan the QR code with your Voxban Android phone app to link and sync your workspace.
            </p>
          </div>
        </div>

        {/* Already paired banner */}
        {phone && (
          <div
            className="mb-6 p-4 rounded-2xl flex flex-wrap items-center justify-between gap-3"
            style={{ background: "var(--vk-low-soft)", border: "1px solid var(--vk-border)" }}
          >
            <div className="flex items-center gap-3">
              <CheckCircle2 className="w-6 h-6 shrink-0" style={{ color: "var(--vk-low)" }} />
              <div>
                <div className="font-semibold text-sm">Connected to {phone.deviceName}</div>
                <div className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                  Android v{phone.appVersion || "3.4.0"} · Last synced{" "}
                  {new Date(phone.lastSyncedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                </div>
              </div>
            </div>
            <div className="flex items-center gap-2">
              {!isModal && (
                <button onClick={onContinueStandalone} className="vk-btn-lime px-4 py-2 text-sm cursor-pointer">
                  Open Workspace
                </button>
              )}
              <button
                onClick={onUnlink}
                className="vk-btn-outline px-3 py-2 text-xs flex items-center gap-1.5 cursor-pointer"
              >
                <Unlink className="w-3.5 h-3.5" /> Unlink Phone
              </button>
            </div>
          </div>
        )}

        {/* Main 2-column QR + Instructions grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 items-start">
          {/* Left: Live QR Card */}
          <div className="vk-well p-5 flex flex-col items-center text-center">
            <div className="flex items-center gap-2 text-xs font-semibold mb-3" style={{ color: "var(--vk-ink-2)" }}>
              <QrCode className="w-4 h-4" />
              <span>SCAN WITH VOXBAN ANDROID APP</span>
            </div>

            <div className="w-60 h-60 bg-white rounded-2xl p-3 shadow-sm border border-black/10 qr-frame flex items-center justify-center">
              {session?.qrSvg ? (
                <div className="w-full h-full" dangerouslySetInnerHTML={{ __html: session.qrSvg }} />
              ) : (
                <span className="text-xs text-neutral-500">Generating QR…</span>
              )}
            </div>

            <div className="mt-4 flex items-center gap-2 text-xs" style={{ color: "var(--vk-ink-2)" }}>
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
              <span>Waiting for phone scan on local Wi-Fi…</span>
            </div>

            {session && (
              <div className="mt-4 w-full p-3 rounded-xl text-left text-xs space-y-1.5" style={{ background: "var(--vk-raised)", border: "1px solid var(--vk-border)" }}>
                <div className="flex items-center justify-between">
                  <span style={{ color: "var(--vk-ink-2)" }}>Manual code:</span>
                  <span className="font-mono font-bold text-base tracking-widest">{session.code}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span style={{ color: "var(--vk-ink-2)" }}>PC address:</span>
                  <span className="font-mono">{session.host}:{session.port}</span>
                </div>
              </div>
            )}

            <button
              onClick={onRefreshQr}
              className="mt-3 vk-btn-outline px-4 py-1.5 text-xs flex items-center gap-1.5 cursor-pointer"
            >
              <RefreshCw className="w-3.5 h-3.5" /> Refresh QR code
            </button>
          </div>

          {/* Right: Steps + Download Android APK from GitHub Releases */}
          <div className="space-y-4">
            <div className="p-4 rounded-2xl" style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}>
              <h2 className="font-semibold text-sm mb-3 flex items-center gap-2">
                <Smartphone className="w-4 h-4" /> How to link your phone
              </h2>
              <ol className="text-sm space-y-2.5 pl-4 list-decimal" style={{ color: "var(--vk-ink-2)" }}>
                <li>
                  Connect your Android phone and {deviceWord} to the <b style={{ color: "var(--vk-ink)" }}>same Wi-Fi network</b>.
                </li>
                <li>
                  Open <b style={{ color: "var(--vk-ink)" }}>Voxban</b> on your phone and tap{" "}
                  <b style={{ color: "var(--vk-ink)" }}>More → Pair Desktop</b> (or Settings → Desktop sync).
                </li>
                <li>
                  Point your phone camera at this QR code - your boards, calendar reminders, notes, workouts &amp; music favourites sync automatically.
                </li>
              </ol>
            </div>

            {/* Don't have the phone app? Redirect to GitHub Releases */}
            <div
              className="p-5 rounded-2xl space-y-3"
              style={{
                background: "linear-gradient(135deg, rgba(234,255,135,0.35), rgba(32,75,55,0.08))",
                border: "1px solid var(--vk-border)",
              }}
            >
              <div className="flex items-center gap-2 font-semibold text-sm">
                <Download className="w-4 h-4" />
                <span>Don’t have the Voxban phone app yet?</span>
              </div>
              <p className="text-xs leading-relaxed" style={{ color: "var(--vk-ink-2)" }}>
                Scan the QR code with your phone’s regular camera app, or click below - to jump straight to{" "}
                <b>GitHub Releases</b> and download the latest Voxban Android APK.
              </p>
              <button
                onClick={() => void api.openExternalUrl(githubUrl)}
                className="vk-btn-lime px-5 py-2.5 text-sm inline-flex items-center gap-2 cursor-pointer"
              >
                <Download className="w-4 h-4" />
                Download Voxban Android APK
                <ExternalLink className="w-3.5 h-3.5" />
              </button>
            </div>

            <div className="flex items-center justify-between text-xs px-1" style={{ color: "var(--vk-ink-3)" }}>
              <span className="inline-flex items-center gap-1.5">
                <Wifi className="w-3.5 h-3.5" /> Local LAN sync - no cloud server
              </span>
              {!isModal && (
                <button
                  onClick={onContinueStandalone}
                  className="underline hover:opacity-80 cursor-pointer bg-transparent border-0 text-xs inline-flex items-center gap-1"
                  style={{ color: "var(--vk-ink-2)" }}
                >
                  <Laptop className="w-3.5 h-3.5" /> Continue standalone on {deviceWord}
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
