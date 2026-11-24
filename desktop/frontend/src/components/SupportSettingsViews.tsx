import { useEffect, useState } from "react";
import {
  Check,
  CheckCircle2,
  CloudDownload,
  Copy,
  Download,
  ExternalLink,
  Mail,
  QrCode,
  Send,
  Trash2,
} from "lucide-react";
import { api } from "../api";
import { MarkdownChangelog } from "./MarkdownChangelog";
import type {
  AppState,
  PairInfoResponse,
  ReleaseInfo,
  SupportCategory,
  SupportPriority,
  SupportTicket,
  UpdateInstallProgress,
} from "../types";
import { formatBytes } from "../types";

const SUPPORT_EMAIL = "abdullahmeyad7@gmail.com";

export function SupportView({
  state,
  onSaveState,
  onReloadState,
}: {
  state: AppState;
  onSaveState: (n: AppState) => void;
  onReloadState: () => Promise<void>;
}) {
  const [category, setCategory] = useState<SupportCategory>("BUG");
  const [priority, setPriority] = useState<SupportPriority>("NORMAL");
  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [contactEmail, setContactEmail] = useState("");
  const [includeDiagnostics, setIncludeDiagnostics] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);
  const [copiedEmail, setCopiedEmail] = useState(false);

  const sendTicket = async (mode: "MAILTO" | "GMAIL" | "COPY") => {
    if (!subject.trim() || !description.trim()) {
      setNotice("Please enter both a subject and message.");
      return;
    }
    const now = Date.now();
    const draft: SupportTicket = {
      id: "",
      category,
      priority,
      subject: subject.trim(),
      description: description.trim(),
      contactEmail: contactEmail.trim(),
      includeDiagnostics,
      status: "SENT",
      createdAt: now,
      updatedAt: now,
    };
    const res = await api.formatSupportTicket(draft, true, false);
    await onReloadState();
    setSubject("");
    setDescription("");

    if (mode === "MAILTO") {
      void api.openExternalUrl(res.email.mailtoUrl);
      setNotice(`Ticket #${res.ticket.id} opened in your default mail app addressed to ${SUPPORT_EMAIL}.`);
    } else if (mode === "GMAIL") {
      void api.openExternalUrl(res.email.gmailWebUrl);
      setNotice(`Ticket #${res.ticket.id} opened in Gmail Web Compose addressed to ${SUPPORT_EMAIL}.`);
    } else {
      await navigator.clipboard.writeText(`To: ${res.email.to}\nSubject: ${res.email.subject}\n\n${res.email.body}`);
      setNotice(`Ticket #${res.ticket.id} copied to clipboard - paste and mail to ${SUPPORT_EMAIL}.`);
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Help &amp; Support Tickets</h2>
          <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
            Direct support inbox: <b>{SUPPORT_EMAIL}</b>
          </p>
        </div>
        <button
          onClick={async () => {
            await navigator.clipboard.writeText(SUPPORT_EMAIL).catch(() => {});
            setCopiedEmail(true);
            setTimeout(() => setCopiedEmail(false), 1800);
          }}
          className="vk-btn-outline px-4 py-2 text-xs inline-flex items-center gap-1.5 cursor-pointer"
        >
          {copiedEmail ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
          {copiedEmail ? "Copied!" : `Copy ${SUPPORT_EMAIL}`}
        </button>
      </div>

      {notice && (
        <div
          className="p-3.5 rounded-2xl text-xs flex items-center justify-between gap-2"
          style={{ background: "var(--vk-low-soft)" }}
        >
          <span className="inline-flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 shrink-0" style={{ color: "var(--vk-low)" }} />
            {notice}
          </span>
          <button onClick={() => setNotice(null)} className="underline bg-transparent border-0 cursor-pointer text-xs">
            Dismiss
          </button>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
        <div className="lg:col-span-7 vk-card p-5 space-y-4">
          <h3 className="font-semibold text-sm">New Support Ticket</h3>
          <div className="flex flex-wrap gap-1.5">
            {(
              [
                ["BUG", "🐛 Bug report"],
                ["FEATURE", "✨ Feature request"],
                ["AI_VOICE", "🎙️ AI & Voice"],
                ["WORKOUT_MUSIC", "🎵 Workout & Music"],
                ["DATA_VAULT", "🔐 Backup & Vault"],
                ["OTHER", "💬 General help"],
              ] as [SupportCategory, string][]
            ).map(([k, label]) => (
              <button
                key={k}
                onClick={() => setCategory(k)}
                className={`px-3 py-1.5 text-xs rounded-full cursor-pointer border ${
                  category === k ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="flex gap-2">
            {(["LOW", "NORMAL", "URGENT"] as SupportPriority[]).map((p) => (
              <button
                key={p}
                onClick={() => setPriority(p)}
                className={`flex-1 py-1.5 text-xs rounded-full cursor-pointer border ${
                  priority === p ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                }`}
              >
                {p[0] + p.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
          <input
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            placeholder="Short summary…"
            className="w-full vk-well px-3.5 py-2.5 text-sm border-0 outline-none"
          />
          <textarea
            rows={5}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Describe your issue, steps to reproduce, or idea… (Enter adds a new line)"
            className="w-full vk-well px-3.5 py-2.5 text-sm border-0 outline-none resize-y"
          />
          <input
            value={contactEmail}
            onChange={(e) => setContactEmail(e.target.value)}
            placeholder="Your email (optional, for reply reference)"
            className="w-full vk-well px-3.5 py-2.5 text-sm border-0 outline-none"
          />
          <label className="flex items-center gap-2 text-xs cursor-pointer" style={{ color: "var(--vk-ink-2)" }}>
            <input
              type="checkbox"
              checked={includeDiagnostics}
              onChange={(e) => setIncludeDiagnostics(e.target.checked)}
            />
            Include privacy-safe app &amp; OS diagnostics (no personal data)
          </label>
          <div className="flex flex-wrap gap-2 pt-1">
            <button
              onClick={() => void sendTicket("MAILTO")}
              className="vk-btn-lime px-5 py-2.5 text-xs inline-flex items-center gap-1.5 cursor-pointer"
            >
              <Send className="w-3.5 h-3.5" /> Send via Email App
            </button>
            <button
              onClick={() => void sendTicket("GMAIL")}
              className="vk-btn-outline px-4 py-2.5 text-xs inline-flex items-center gap-1.5 cursor-pointer"
            >
              <Mail className="w-3.5 h-3.5" /> Open Gmail Web
            </button>
            <button
              onClick={() => void sendTicket("COPY")}
              className="vk-btn-outline px-4 py-2.5 text-xs inline-flex items-center gap-1.5 cursor-pointer"
            >
              <Copy className="w-3.5 h-3.5" /> Copy Ticket
            </button>
          </div>
        </div>

        {/* History */}
        <div className="lg:col-span-5 vk-card p-5 space-y-3">
          <h3 className="font-semibold text-sm">My Tickets ({state.supportTickets.length})</h3>
          {state.supportTickets.length === 0 ? (
            <p className="text-xs py-6 text-center" style={{ color: "var(--vk-ink-3)" }}>
              Tickets you submit will appear here with their #VB-XXXX tracking ID.
            </p>
          ) : (
            state.supportTickets.map((t) => (
              <div
                key={t.id}
                className="p-3.5 rounded-2xl space-y-2"
                style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}
              >
                <div className="flex items-center justify-between text-xs">
                  <span className="font-mono font-semibold">#{t.id}</span>
                  <span className="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-[#EAFF87] text-[#1B201C]">
                    {t.status}
                  </span>
                </div>
                <div className="font-semibold text-sm">{t.subject}</div>
                <p className="text-xs line-clamp-2" style={{ color: "var(--vk-ink-2)" }}>{t.description}</p>
                <div className="flex items-center gap-2 pt-1">
                  <button
                    onClick={async () => {
                      const res = await api.formatSupportTicket(t, false, true);
                      window.open(res.email.gmailWebUrl, "_blank", "noopener,noreferrer");
                    }}
                    className="vk-btn-outline px-3 py-1 text-[11px] cursor-pointer"
                  >
                    Follow up
                  </button>
                  <button
                    onClick={() =>
                      onSaveState({
                        ...state,
                        supportTickets: state.supportTickets.filter((x) => x.id !== t.id),
                      })
                    }
                    className="ml-auto p-1 rounded-full hover:bg-red-500/15 bg-transparent border-0 cursor-pointer"
                  >
                    <Trash2 className="w-3.5 h-3.5" style={{ color: "var(--vk-danger)" }} />
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

export function SettingsView({
  state,
  onSaveState,
  pairInfo,
  onOpenPair,
}: {
  state: AppState;
  onSaveState: (n: AppState) => void;
  pairInfo: PairInfoResponse | null;
  onOpenPair: () => void;
}) {
  const [configuredKeys, setConfiguredKeys] = useState<Record<string, boolean>>({});
  const [apiKeyDraft, setApiKeyDraft] = useState("");
  const [release, setRelease] = useState<ReleaseInfo | null>(null);
  const [checkingUpdate, setCheckingUpdate] = useState(false);
  const [updateProg, setUpdateProg] = useState<UpdateInstallProgress | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    void api.getKeys().then((r) => setConfiguredKeys(r.configured)).catch(() => {});
  }, []);

  useEffect(() => {
    if (!updateProg || (updateProg.phase !== "downloading" && updateProg.phase !== "installing")) return;
    const id = setInterval(() => {
      void api
        .updateProgress()
        .then((p) => {
          setUpdateProg(p);
          if (p.phase === "error" && p.error) setMsg(p.error);
          else if (p.phase === "ready" || p.phase === "restarting") setMsg(p.message);
        })
        .catch(() => {});
    }, 250);
    return () => clearInterval(id);
  }, [updateProg?.phase]);

  const updateSettings = (patch: Partial<AppState["settings"]>) => {
    onSaveState({ ...state, settings: { ...state.settings, ...patch } });
  };

  const checkUpdates = async () => {
    setCheckingUpdate(true);
    try {
      const res = await api.checkUpdates();
      if (res.ok && res.release) setRelease(res.release);
      else setMsg(res.error || "Could not check GitHub Releases.");
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setCheckingUpdate(false);
    }
  };

  const installInAppUpdate = async () => {
    setMsg(null);
    try {
      const res = await api.installUpdate();
      if (res.ok && res.progress) setUpdateProg(res.progress);
      else setMsg(res.error || "Could not start in-app update.");
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  };

  const exportBackup = () => {
    const blob = new Blob([JSON.stringify({ version: 1, state }, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `voxban-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="max-w-3xl mx-auto space-y-5">
      <h2 className="text-2xl font-bold tracking-tight">Settings</h2>

      {msg && <div className="vk-well px-4 py-2.5 text-xs">{msg}</div>}

      {/* Phone Link & Sync */}
      <div className="vk-card p-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="font-semibold text-sm">Android Phone Sync</h3>
          <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
            {pairInfo?.pairedPhone
              ? `Paired with ${pairInfo.pairedPhone.deviceName}`
              : "Scan QR code with your Voxban Android app to sync tasks, notes & reminders"}
          </p>
        </div>
        <button onClick={onOpenPair} className="vk-btn-lime px-4 py-2 text-xs inline-flex items-center gap-1.5 cursor-pointer">
          <QrCode className="w-4 h-4" /> {pairInfo?.pairedPhone ? "Manage Linked Phone" : "Scan Phone QR"}
        </button>
      </div>

      {/* Profile & Appearance */}
      <div className="vk-card p-5 space-y-4">
        <h3 className="font-semibold text-sm">Profile &amp; Appearance</h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label className="text-xs block mb-1" style={{ color: "var(--vk-ink-2)" }}>Your name</label>
            <input
              value={state.settings.userName}
              onChange={(e) => updateSettings({ userName: e.target.value })}
              placeholder="e.g. Alex"
              className="w-full vk-well px-3.5 py-2 text-sm border-0 outline-none"
            />
          </div>
          <div>
            <label className="text-xs block mb-1" style={{ color: "var(--vk-ink-2)" }}>Currency symbol</label>
            <input
              value={state.settings.currency}
              onChange={(e) => updateSettings({ currency: e.target.value })}
              placeholder="$ or ৳"
              className="w-full vk-well px-3.5 py-2 text-sm border-0 outline-none"
            />
          </div>
        </div>
        <div className="flex gap-2">
          {["SYSTEM", "LIGHT", "DARK"].map((mode) => (
            <button
              key={mode}
              onClick={() => updateSettings({ themeMode: mode })}
              className={`flex-1 py-2 text-xs rounded-full cursor-pointer border ${
                state.settings.themeMode === mode ? "vk-btn-lime border-transparent" : "vk-btn-outline"
              }`}
            >
              {mode[0] + mode.slice(1).toLowerCase()}
            </button>
          ))}
        </div>
      </div>

      {/* AI Engine */}
      <div className="vk-card p-5 space-y-3">
        <h3 className="font-semibold text-sm">AI Engine</h3>
        <div className="flex flex-wrap gap-2">
          {["GEMINI", "OPENAI", "CLAUDE", "OPENROUTER"].map((eng) => (
            <button
              key={eng}
              onClick={() => updateSettings({ engine: eng })}
              className={`px-4 py-1.5 text-xs rounded-full cursor-pointer border ${
                state.settings.engine === eng ? "vk-btn-lime border-transparent" : "vk-btn-outline"
              }`}
            >
              {eng} {configuredKeys[eng] ? "✓" : ""}
            </button>
          ))}
        </div>
        <div className="flex gap-2">
          <input
            type="password"
            value={apiKeyDraft}
            onChange={(e) => setApiKeyDraft(e.target.value)}
            placeholder={`Paste ${state.settings.engine} API key…`}
            className="flex-1 vk-well px-3.5 py-2 text-sm border-0 outline-none"
          />
          <button
            onClick={async () => {
              const res = await api.putKeys({ [state.settings.engine]: apiKeyDraft });
              setConfiguredKeys(res.configured);
              setApiKeyDraft("");
              setMsg(`Saved ${state.settings.engine} API key locally.`);
            }}
            className="vk-btn-lime px-4 py-2 text-xs cursor-pointer"
          >
            Save Key
          </button>
        </div>
      </div>

      {/* GitHub Releases Updater + Backup */}
      <div className="vk-card p-5 space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="font-semibold text-sm">App Updates (GitHub Releases)</h3>
            <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
              Voxban Desktop v3.5.1 · checks github.com/meyadstack/Voxban
            </p>
          </div>
          <button
            onClick={() => void checkUpdates()}
            disabled={checkingUpdate}
            className="vk-btn-outline px-4 py-2 text-xs inline-flex items-center gap-1.5 cursor-pointer"
          >
            <CloudDownload className="w-4 h-4" /> {checkingUpdate ? "Checking…" : "Check for updates"}
          </button>
        </div>
        {release && (
          <div className="vk-well p-4 text-xs space-y-3">
            <div className="flex items-center justify-between gap-2">
              <div className="font-semibold text-sm">
                {release.title || release.latestTag}{" "}
                <span className="font-normal text-xs" style={{ color: "var(--vk-ink-2)" }}>
                  {release.updateAvailable ? "- Update available!" : "- You’re up to date"}
                </span>
              </div>
              <span className="font-mono text-[11px] px-2 py-0.5 rounded-full" style={{ background: "var(--vk-raised)" }}>
                {release.latestTag}
              </span>
            </div>
            {release.notes && (
              <div
                className="p-3.5 rounded-2xl max-h-72 overflow-y-auto"
                style={{ background: "var(--vk-raised)", border: "1px solid var(--vk-border)" }}
              >
                <MarkdownChangelog markdown={release.notes} />
              </div>
            )}
            {updateProg && updateProg.phase !== "idle" && (
              <div
                className="p-3 rounded-xl space-y-1.5"
                style={{ background: "var(--vk-raised)", border: "1px solid var(--vk-border)" }}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">{updateProg.message}</span>
                  {updateProg.phase === "downloading" && updateProg.totalBytes > 0 && (
                    <span className="font-mono text-[11px]" style={{ color: "var(--vk-ink-2)" }}>
                      {formatBytes(updateProg.downloadedBytes)} / {formatBytes(updateProg.totalBytes)}
                    </span>
                  )}
                </div>
                {(updateProg.phase === "downloading" || updateProg.phase === "installing") && (
                  <div className="w-full h-1.5 rounded-full overflow-hidden" style={{ background: "var(--vk-border)" }}>
                    <div
                      className="h-full rounded-full transition-all"
                      style={{
                        width:
                          updateProg.phase === "installing"
                            ? "100%"
                            : `${Math.max(
                                6,
                                updateProg.totalBytes > 0
                                  ? Math.min(100, Math.round((updateProg.downloadedBytes / updateProg.totalBytes) * 100))
                                  : 20,
                              )}%`,
                        background: "#1B201C",
                      }}
                    />
                  </div>
                )}
              </div>
            )}
            <div className="flex flex-wrap gap-2">
              {release.updateAvailable && (
                <button
                  onClick={() => void installInAppUpdate()}
                  disabled={
                    updateProg?.phase === "downloading" ||
                    updateProg?.phase === "installing" ||
                    updateProg?.phase === "restarting"
                  }
                  className="vk-btn-lime px-4 py-1.5 text-xs font-semibold inline-flex items-center gap-1.5 cursor-pointer disabled:opacity-60"
                >
                  <CloudDownload className="w-3.5 h-3.5" />
                  {updateProg?.phase === "downloading"
                    ? "Downloading…"
                    : updateProg?.phase === "installing"
                    ? "Installing…"
                    : updateProg?.phase === "restarting"
                    ? "Restarting…"
                    : "Download & Install In-App"}
                </button>
              )}
              <button
                onClick={() => void api.openExternalUrl(release.htmlUrl)}
                className={`${release.updateAvailable ? "vk-btn-outline" : "vk-btn-lime"} px-3.5 py-1.5 text-xs inline-flex items-center gap-1 cursor-pointer`}
              >
                Open Release Page <ExternalLink className="w-3 h-3" />
              </button>
              {release.macDownloadUrl && (
                <button
                  onClick={() => void api.openExternalUrl(release.macDownloadUrl!)}
                  className="vk-btn-outline px-3.5 py-1.5 text-xs inline-flex items-center gap-1 cursor-pointer"
                >
                  <Download className="w-3 h-3" /> macOS (.dmg)
                </button>
              )}
              {release.windowsDownloadUrl && (
                <button
                  onClick={() => void api.openExternalUrl(release.windowsDownloadUrl!)}
                  className="vk-btn-outline px-3.5 py-1.5 text-xs inline-flex items-center gap-1 cursor-pointer"
                >
                  <Download className="w-3 h-3" /> Windows (.exe)
                </button>
              )}
              {release.apkDownloadUrl && (
                <button
                  onClick={() => void api.openExternalUrl(release.apkDownloadUrl!)}
                  className="vk-btn-outline px-3.5 py-1.5 text-xs inline-flex items-center gap-1 cursor-pointer"
                >
                  <Download className="w-3 h-3" /> Android APK
                </button>
              )}
            </div>
          </div>
        )}
        <div className="pt-2 border-t flex flex-wrap gap-2" style={{ borderColor: "var(--vk-border)" }}>
          <button onClick={exportBackup} className="vk-btn-outline px-4 py-2 text-xs cursor-pointer">
            Export Backup (.json)
          </button>
          <label className="vk-btn-outline px-4 py-2 text-xs cursor-pointer">
            Import Backup (.json)
            <input
              type="file"
              accept="application/json"
              className="hidden"
              onChange={async (e) => {
                const file = e.target.files?.[0];
                if (!file) return;
                const text = await file.text();
                const parsed = JSON.parse(text);
                const incoming = parsed.state ?? parsed;
                if (incoming?.workspace) {
                  onSaveState(incoming);
                  setMsg("Backup restored successfully!");
                }
              }}
            />
          </label>
        </div>
      </div>
    </div>
  );
}
