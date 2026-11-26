import type {
  AppState,
  DownloadedTrack,
  GemmaDownloadProgress,
  GemmaStatusResponse,
  PairInfoResponse,
  PasswordEntry,
  ReleaseInfo,
  SupportTicket,
  UpdateInstallProgress,
  VaultShareCreateResponse,
  WorkoutTrack,
} from "./types";

declare global {
  interface Window {
    __VOXBAN_API_BASE__?: string;
  }
}

export function apiBase(): string {
  if (typeof window !== "undefined" && window.__VOXBAN_API_BASE__) {
    return window.__VOXBAN_API_BASE__;
  }
  return "";
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${apiBase()}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    try {
      const parsed = JSON.parse(text) as { error?: string; message?: string };
      if (parsed.error || parsed.message) {
        throw new Error(parsed.error || parsed.message);
      }
    } catch (err) {
      if (err instanceof Error && text.startsWith("{")) throw err;
    }
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export interface BiometricStatus {
  available: boolean;
  enrolled: boolean;
  label: string;
}

export const api = {
  health: () => req<{ ok: boolean; version: string; pcName: string; os: string; downloadsDir: string }>("/api/health"),
  getState: () => req<AppState>("/api/state"),
  putState: (st: AppState) => req<AppState>("/api/state", { method: "PUT", body: JSON.stringify(st) }),
  getKeys: () => req<{ configured: Record<string, boolean> }>("/api/keys"),
  putKeys: (keys: Record<string, string>) =>
    req<{ ok: boolean; configured: Record<string, boolean> }>("/api/keys", {
      method: "PUT",
      body: JSON.stringify(keys),
    }),

  // Phone QR pairing & sync
  pairInfo: () => req<PairInfoResponse>("/api/pair/info"),
  pairRefresh: () => req<{ ok: boolean; session: PairInfoResponse["session"] }>("/api/pair/refresh", { method: "POST" }),
  pairUnlink: () => req<{ ok: boolean; session: PairInfoResponse["session"] }>("/api/pair/unlink", { method: "POST" }),

  // AI assistant & local Gemma 4 background downloader
  chat: (prompt: string) =>
    req<{ reply: string; changes: string[]; engine: string; state: AppState }>("/api/ai/chat", {
      method: "POST",
      body: JSON.stringify({ prompt }),
    }),
  gemmaStatus: () => req<GemmaStatusResponse>("/api/ai/gemma/status"),
  gemmaDownload: (model: string) =>
    req<{ ok: boolean; progress: GemmaDownloadProgress }>("/api/ai/gemma/download", {
      method: "POST",
      body: JSON.stringify({ model }),
    }),

  // Music search, streaming & offline downloading
  searchMusic: (q: string) =>
    req<{ tracks: WorkoutTrack[]; error?: string }>(`/api/music/search?q=${encodeURIComponent(q)}`),
  radioQueue: (videoId: string) =>
    req<{ tracks: WorkoutTrack[] }>(`/api/music/radio?videoId=${encodeURIComponent(videoId)}`),
  streamUrl: (videoId: string) => `${apiBase()}/api/music/stream/${encodeURIComponent(videoId)}`,
  downloadFileUrl: (videoId: string) => `${apiBase()}/api/music/download-file/${encodeURIComponent(videoId)}`,
  downloadMusic: (track: WorkoutTrack) =>
    req<{ ok: boolean; downloaded?: DownloadedTrack; folder?: string; error?: string }>("/api/music/download", {
      method: "POST",
      body: JSON.stringify(track),
    }),
  deleteDownload: (videoId: string) =>
    req<{ ok: boolean }>(`/api/music/download/${encodeURIComponent(videoId)}`, { method: "DELETE" }),
  openMusicFolder: () => req<{ ok: boolean; path: string }>("/api/music/open-folder", { method: "POST" }),

  // Password vault (AES-256-GCM + optional native Fingerprint / Touch ID)
  vaultStatus: () => req<{ exists: boolean; unlocked?: boolean; biometric: BiometricStatus }>("/api/vault/status"),
  vaultUnlock: (passphrase: string, enableBiometric = false) =>
    req<{
      ok: boolean;
      entries?: PasswordEntry[];
      biometric?: BiometricStatus;
      biometricWarning?: string | null;
      error?: string;
    }>("/api/vault/unlock", {
      method: "POST",
      body: JSON.stringify({ passphrase, enableBiometric }),
    }),
  vaultLock: () => req<{ ok: boolean; biometric?: BiometricStatus }>("/api/vault/lock", { method: "POST" }),
  vaultSave: (entries: PasswordEntry[], passphrase?: string) =>
    req<{ ok: boolean; error?: string }>("/api/vault/save", {
      method: "POST",
      body: JSON.stringify({ passphrase: passphrase || undefined, entries }),
    }),
  vaultBiometricUnlock: () =>
    req<{ ok: boolean; entries?: PasswordEntry[]; biometric?: BiometricStatus; error?: string }>(
      "/api/vault/biometric/unlock",
      { method: "POST" },
    ),
  vaultBiometricEnable: (passphrase?: string) =>
    req<{ ok: boolean; biometric?: BiometricStatus; error?: string }>("/api/vault/biometric/enable", {
      method: "POST",
      body: JSON.stringify({ passphrase: passphrase || undefined }),
    }),
  vaultBiometricDisable: () =>
    req<{ ok: boolean; biometric?: BiometricStatus }>("/api/vault/biometric/disable", { method: "POST" }),
  vaultGenerate: () => req<{ password: string }>("/api/vault/generate", { method: "POST" }),
  vaultShareCreate: (entry: PasswordEntry, mode: "lan" | "direct", ttlSec = 120) =>
    req<VaultShareCreateResponse>("/api/vault/share/create", {
      method: "POST",
      body: JSON.stringify({ entry, mode, ttlSec }),
    }),
  vaultShareStatus: (id: string) =>
    req<{ ok: boolean; claimed: boolean; burned: boolean; expired: boolean }>(
      `/api/vault/share/status/${encodeURIComponent(id)}`,
    ),
  vaultShareBurn: (id: string) =>
    req<{ ok: boolean }>(`/api/vault/share/${encodeURIComponent(id)}`, { method: "DELETE" }),
  vaultShareImport: (qrText: string) =>
    req<{ ok: boolean; entry?: PasswordEntry; burnedOnSender?: boolean; error?: string }>("/api/vault/share/import", {
      method: "POST",
      body: JSON.stringify({ qrText }),
    }),

  // Support tickets -> abdullahmeyad7@gmail.com
  formatSupportTicket: (ticket: SupportTicket, save: boolean, isFollowUp = false) =>
    req<{
      ticket: SupportTicket;
      email: {
        to: string;
        subject: string;
        body: string;
        mailtoUrl: string;
        gmailWebUrl: string;
        diagnostics: string;
      };
    }>("/api/support/format", {
      method: "POST",
      body: JSON.stringify({ ticket, save, isFollowUp }),
    }),

  // GitHub release updater (in-app download & install + browser fallback)
  checkUpdates: () => req<{ ok: boolean; release?: ReleaseInfo; error?: string }>("/api/updates/check"),
  installUpdate: () =>
    req<{ ok: boolean; progress?: UpdateInstallProgress; error?: string }>("/api/updates/install", { method: "POST" }),
  updateProgress: () => req<UpdateInstallProgress>("/api/updates/progress"),
  openExternalUrl: (url: string) =>
    req<{ ok: boolean; error?: string }>("/api/system/open-url", {
      method: "POST",
      body: JSON.stringify({ url }),
    }).catch(() => {
      window.open(url, "_blank", "noopener,noreferrer");
      return { ok: true };
    }),

  // Live WebSocket updates
  connectWs: (onMessage: (evt: { event: string; data: unknown }) => void): WebSocket => {
    const base = apiBase();
    const wsUrl = base
      ? base.replace(/^http/, "ws") + "/api/ws"
      : `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/api/ws`;
    const ws = new WebSocket(wsUrl);
    ws.onmessage = (e) => {
      try {
        onMessage(JSON.parse(e.data));
      } catch {
        // ignore non-json frames
      }
    };
    return ws;
  },
};
