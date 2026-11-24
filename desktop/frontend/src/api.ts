import type {
  AppState,
  DownloadedTrack,
  PairInfoResponse,
  PasswordEntry,
  ReleaseInfo,
  SupportTicket,
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
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
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

  // AI assistant
  chat: (prompt: string) =>
    req<{ reply: string; changes: string[]; engine: string; state: AppState }>("/api/ai/chat", {
      method: "POST",
      body: JSON.stringify({ prompt }),
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

  // Password vault
  vaultStatus: () => req<{ exists: boolean }>("/api/vault/status"),
  vaultUnlock: (passphrase: string) =>
    req<{ ok: boolean; entries?: PasswordEntry[]; error?: string }>("/api/vault/unlock", {
      method: "POST",
      body: JSON.stringify({ passphrase }),
    }),
  vaultSave: (passphrase: string, entries: PasswordEntry[]) =>
    req<{ ok: boolean; error?: string }>("/api/vault/save", {
      method: "POST",
      body: JSON.stringify({ passphrase, entries }),
    }),
  vaultGenerate: () => req<{ password: string }>("/api/vault/generate", { method: "POST" }),

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

  // GitHub release updater
  checkUpdates: () => req<{ ok: boolean; release?: ReleaseInfo; error?: string }>("/api/updates/check"),

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
