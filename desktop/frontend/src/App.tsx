import { useCallback, useEffect, useRef, useState } from "react";
import {
  Bell,
  Dumbbell,
  FileText,
  Headphones,
  LayoutDashboard,
  LayoutGrid,
  LifeBuoy,
  Lock,
  Mic,
  Pause,
  Play,
  QrCode,
  Settings as SettingsIcon,
  SkipForward,
  Smartphone,
  Timer,
  Wallet,
  X,
} from "lucide-react";
import { api } from "./api";
import { AssistantView } from "./components/AssistantView";
import { BoardView } from "./components/BoardView";
import { FocusView, MoneyView, VaultView } from "./components/MoneyVaultFocusViews";
import { NotesView } from "./components/NotesView";
import { PhonePairGate } from "./components/PhonePairGate";
import { SettingsView, SupportView } from "./components/SupportSettingsViews";
import { VoxbanAvatar } from "./components/VoxbanAvatar";
import { type MusicController, WorkoutMusicView } from "./components/WorkoutMusicView";
import type { AppState, PairInfoResponse, WorkoutTrack } from "./types";

type Section = "HOME" | "BOARD" | "ASSISTANT" | "WORKOUT" | "NOTES" | "MONEY" | "VAULT" | "FOCUS" | "SUPPORT" | "SETTINGS";

const NAV_ITEMS: Array<{ id: Section; label: string; icon: typeof LayoutDashboard }> = [
  { id: "HOME", label: "Home", icon: LayoutDashboard },
  { id: "BOARD", label: "Tasks", icon: LayoutGrid },
  { id: "ASSISTANT", label: "Voice AI", icon: Mic },
  { id: "WORKOUT", label: "Workout", icon: Dumbbell },
  { id: "NOTES", label: "Notes", icon: FileText },
  { id: "MONEY", label: "Money", icon: Wallet },
  { id: "VAULT", label: "Vault", icon: Lock },
  { id: "FOCUS", label: "Focus", icon: Timer },
  { id: "SUPPORT", label: "Support", icon: LifeBuoy },
  { id: "SETTINGS", label: "Settings", icon: SettingsIcon },
];

export default function App() {
  const [state, setState] = useState<AppState | null>(null);
  const [pairInfo, setPairInfo] = useState<PairInfoResponse | null>(null);
  const [standaloneBypass, setStandaloneBypass] = useState(false);
  const [pairModalOpen, setPairModalOpen] = useState(false);
  const [section, setSection] = useState<Section>("HOME");
  const isMac = typeof navigator !== "undefined" && /Mac|Darwin/i.test(navigator.platform || navigator.userAgent);

  // Persistent background audio player across all tabs
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [currentTrack, setCurrentTrack] = useState<WorkoutTrack | null>(null);
  const [queue, setQueue] = useState<WorkoutTrack[]>([]);
  const [queueIdx, setQueueIdx] = useState(-1);
  const [playing, setPlaying] = useState(false);
  const [loadingAudio, setLoadingAudio] = useState(false);
  const [posSec, setPosSec] = useState(0);
  const [durSec, setDurSec] = useState(0);

  const loadAll = useCallback(async () => {
    const [st, pair] = await Promise.all([api.getState(), api.pairInfo()]);
    setState(st);
    setPairInfo(pair);
  }, []);

  useEffect(() => {
    void loadAll();
    const ws = api.connectWs((evt) => {
      if (evt.event === "paired" || evt.event === "unlinked" || evt.event === "state_updated") {
        void loadAll();
      }
    });
    return () => ws.close();
  }, [loadAll]);

  // Apply light/dark theme matching Android ThemeMode
  useEffect(() => {
    const mode = state?.settings.themeMode || "SYSTEM";
    const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    const dark = mode === "DARK" || (mode === "SYSTEM" && prefersDark);
    document.documentElement.classList.toggle("dark", dark);
  }, [state?.settings.themeMode]);

  const saveState = useCallback((next: AppState) => {
    setState(next);
    void api.putState(next);
  }, []);

  // Audio playback controls
  const startTrack = useCallback((t: WorkoutTrack, list?: WorkoutTrack[]) => {
    const q = list && list.length > 0 ? list : [t];
    const idx = Math.max(0, q.findIndex((x) => x.videoId === t.videoId));
    setQueue(q);
    setQueueIdx(idx);
    setCurrentTrack(t);
    setLoadingAudio(true);
    setPosSec(0);
    setDurSec(t.durationSec || 0);

    const audio = audioRef.current;
    if (audio) {
      audio.src = api.streamUrl(t.videoId);
      audio.play().catch(() => {});
    }

    // Update browser Media Session controls (Windows media overlay + media keys!)
    if ("mediaSession" in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: t.title,
        artist: t.artist || "Workout Music",
        album: "Voxban Workout",
        artwork: t.thumbnailUrl ? [{ src: t.thumbnailUrl }] : [],
      });
    }
  }, []);

  const nextTrack = useCallback(() => {
    if (queue.length === 0) return;
    const nextIdx = (queueIdx + 1) % queue.length;
    startTrack(queue[nextIdx], queue);
  }, [queue, queueIdx, startTrack]);

  const prevTrack = useCallback(() => {
    if (queue.length === 0) return;
    const prevIdx = (queueIdx - 1 + queue.length) % queue.length;
    startTrack(queue[prevIdx], queue);
  }, [queue, queueIdx, startTrack]);

  const musicController: MusicController = {
    current: currentTrack,
    queue,
    playing,
    loading: loadingAudio,
    positionSec: posSec,
    durationSec: durSec,
    playTrack: startTrack,
    togglePlay: () => {
      const a = audioRef.current;
      if (!a) return;
      if (a.paused) void a.play();
      else a.pause();
    },
    next: nextTrack,
    prev: prevTrack,
    seek: (sec) => {
      if (audioRef.current) audioRef.current.currentTime = sec;
      setPosSec(sec);
    },
    stop: () => {
      if (audioRef.current) {
        audioRef.current.pause();
        audioRef.current.src = "";
      }
      setCurrentTrack(null);
      setPlaying(false);
    },
  };

  if (!state || !pairInfo) {
    return (
      <div className="min-h-screen flex items-center justify-center" style={{ background: "var(--vk-bg)" }}>
        <div className="flex flex-col items-center gap-3">
          <VoxbanAvatar size={56} mood="thinking" />
          <p className="text-sm" style={{ color: "var(--vk-ink-2)" }}>Starting Voxban Rust Engine…</p>
        </div>
      </div>
    );
  }

  // Phone QR Scan Gate on first launch until user scans with their Voxban Android app (or continues standalone)
  if (!pairInfo.paired && !standaloneBypass) {
    return (
      <PhonePairGate
        pairInfo={pairInfo}
        onRefreshQr={async () => {
          await api.pairRefresh();
          await loadAll();
        }}
        onUnlink={async () => {
          await api.pairUnlink();
          await loadAll();
        }}
        onContinueStandalone={() => setStandaloneBypass(true)}
      />
    );
  }

  const activeBoard =
    state.workspace.boards.find((b) => b.id === state.workspace.activeBoardId) ??
    state.workspace.boards[0];
  const allCards = activeBoard.stages.flatMap((s) => s.cards);
  const doneCards = activeBoard.stages[activeBoard.stages.length - 1]?.cards.length ?? 0;
  const openCards = allCards.length - doneCards;
  const openReminders = state.calendarReminders.filter((r) => !r.done);

  return (
    <div className="min-h-screen flex" style={{ background: "var(--vk-bg)" }}>
      {/* Persistent audio element */}
      <audio
        ref={audioRef}
        onPlaying={() => {
          setPlaying(true);
          setLoadingAudio(false);
        }}
        onPause={() => setPlaying(false)}
        onWaiting={() => setLoadingAudio(true)}
        onCanPlay={() => setLoadingAudio(false)}
        onTimeUpdate={(e) => {
          const a = e.currentTarget;
          setPosSec(a.currentTime);
          if (Number.isFinite(a.duration) && a.duration > 0) setDurSec(a.duration);
        }}
        onEnded={nextTrack}
      />

      {/* Left Navigation Rail (aligned with Android wide/tablet rail) */}
      <aside
        className="w-20 sm:w-56 shrink-0 flex flex-col justify-between p-3 sticky top-0 h-screen overflow-y-auto"
        style={{ background: "var(--vk-surface)", borderRight: "1px solid var(--vk-border)" }}
      >
        <div className="space-y-2">
          <div className="flex items-center gap-2.5 px-2 py-2">
            <VoxbanAvatar size={36} onClick={() => setSection("ASSISTANT")} />
            <div className="hidden sm:block min-w-0">
              <div className="font-bold text-base leading-tight">Voxban</div>
              <div className="text-[10px] truncate" style={{ color: "var(--vk-ink-3)" }}>
                {isMac ? "macOS · Rust" : "Windows · Rust"}
              </div>
            </div>
          </div>

          <nav className="space-y-1 pt-2">
            {NAV_ITEMS.map(({ id, label, icon: Icon }) => {
              const active = section === id;
              return (
                <button
                  key={id}
                  onClick={() => setSection(id)}
                  className={`w-full flex items-center justify-center sm:justify-start gap-3 px-3 py-2.5 rounded-2xl text-sm font-medium cursor-pointer border-0 transition-colors ${
                    active ? "bg-[#EAFF87] text-[#1B201C] font-semibold" : "bg-transparent hover:bg-black/5"
                  }`}
                  style={active ? undefined : { color: "var(--vk-ink-2)" }}
                >
                  <Icon className="w-4 h-4 shrink-0" />
                  <span className="hidden sm:inline truncate">{label}</span>
                </button>
              );
            })}
          </nav>
        </div>

        {/* Phone link status pill */}
        <button
          onClick={() => setPairModalOpen(true)}
          className="mt-4 w-full p-2.5 rounded-2xl flex items-center justify-center sm:justify-start gap-2.5 text-left cursor-pointer"
          style={{
            background: pairInfo.paired ? "var(--vk-low-soft)" : "var(--vk-well)",
            border: "1px solid var(--vk-border)",
          }}
        >
          {pairInfo.paired ? (
            <Smartphone className="w-4 h-4 shrink-0" style={{ color: "var(--vk-low)" }} />
          ) : (
            <QrCode className="w-4 h-4 shrink-0" />
          )}
          <div className="hidden sm:block min-w-0">
            <div className="text-xs font-semibold truncate">
              {pairInfo.pairedPhone ? pairInfo.pairedPhone.deviceName : "Link Phone (QR)"}
            </div>
            <div className="text-[10px] truncate" style={{ color: "var(--vk-ink-2)" }}>
              {pairInfo.paired ? "Synced over Wi-Fi" : "Scan with Android APK"}
            </div>
          </div>
        </button>
      </aside>

      {/* Main Content Area */}
      <div className="flex-1 min-w-0 flex flex-col">
        <main className="flex-1 p-5 sm:p-8 max-w-6xl w-full mx-auto">
          {section === "HOME" && (
            <div className="space-y-6">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h1 className="text-2xl sm:text-3xl font-bold tracking-tight">
                    Good day{state.settings.userName ? `, ${state.settings.userName}` : ""}
                  </h1>
                  <p className="text-xs mt-0.5" style={{ color: "var(--vk-ink-2)" }}>
                    {new Date().toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <button onClick={() => setSection("ASSISTANT")} className="vk-btn-lime px-4 py-2 text-xs cursor-pointer">
                    🎙️ Talk to AI
                  </button>
                  <button onClick={() => setSection("WORKOUT")} className="vk-btn-outline px-4 py-2 text-xs cursor-pointer">
                    🎵 Workout Music
                  </button>
                  <button onClick={() => setPairModalOpen(true)} className="vk-btn-outline px-4 py-2 text-xs cursor-pointer">
                    📱 Link Phone
                  </button>
                </div>
              </div>

              {/* KPI tiles */}
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                <button onClick={() => setSection("BOARD")} className="vk-card p-5 text-left cursor-pointer">
                  <div className="text-3xl font-bold">{openCards}</div>
                  <div className="text-xs mt-1" style={{ color: "var(--vk-ink-2)" }}>Open tasks</div>
                </button>
                <button
                  onClick={() => setSection("BOARD")}
                  className="rounded-3xl p-5 text-left cursor-pointer border-0"
                  style={{ background: "#EAFF87", color: "#1B201C" }}
                >
                  <div className="text-3xl font-bold">{openReminders.length}</div>
                  <div className="text-xs mt-1 opacity-75">Calendar reminders</div>
                </button>
                <button onClick={() => setSection("NOTES")} className="vk-card p-5 text-left cursor-pointer">
                  <div className="text-3xl font-bold">{state.notes.length}</div>
                  <div className="text-xs mt-1" style={{ color: "var(--vk-ink-2)" }}>Notes</div>
                </button>
                <button onClick={() => setSection("WORKOUT")} className="vk-card p-5 text-left cursor-pointer">
                  <div className="text-3xl font-bold">{state.downloadedWorkoutTracks.length}</div>
                  <div className="text-xs mt-1" style={{ color: "var(--vk-ink-2)" }}>Offline songs</div>
                </button>
              </div>

              {/* Cards row */}
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
                <div className="vk-card p-5 space-y-3">
                  <div className="flex items-center justify-between">
                    <h3 className="font-semibold">Tasks — {activeBoard.title}</h3>
                    <button onClick={() => setSection("BOARD")} className="text-xs underline bg-transparent border-0 cursor-pointer" style={{ color: "var(--vk-accent)" }}>
                      Open board →
                    </button>
                  </div>
                  <div className="grid grid-cols-2 gap-2 text-xs">
                    {activeBoard.stages.map((s) => (
                      <div key={s.id} className="vk-well p-3 flex items-center justify-between">
                        <span className="flex items-center gap-2">
                          <span className="w-2.5 h-2.5 rounded-full" style={{ background: s.color }} />
                          {s.title}
                        </span>
                        <span className="font-bold">{s.cards.length}</span>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="vk-card p-5 space-y-3">
                  <div className="flex items-center justify-between">
                    <h3 className="font-semibold flex items-center gap-2">
                      <Bell className="w-4 h-4" /> Calendar Reminders
                    </h3>
                    <button onClick={() => setSection("BOARD")} className="text-xs underline bg-transparent border-0 cursor-pointer" style={{ color: "var(--vk-accent)" }}>
                      Open calendar →
                    </button>
                  </div>
                  {openReminders.length === 0 ? (
                    <p className="text-xs py-4" style={{ color: "var(--vk-ink-3)" }}>
                      No upcoming calendar reminders.
                    </p>
                  ) : (
                    openReminders.slice(0, 4).map((r) => (
                      <div key={r.id} className="vk-well px-3.5 py-2.5 flex items-center justify-between text-xs">
                        <span className="font-medium">{r.title}</span>
                        <span style={{ color: "var(--vk-ink-2)" }}>
                          {new Date(r.at).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          )}

          {section === "BOARD" && (
            <BoardView
              state={state}
              onSaveState={saveState}
              onRunCommand={async (prompt) => {
                const res = await api.chat(prompt);
                setState(res.state);
              }}
            />
          )}

          {section === "ASSISTANT" && <AssistantView state={state} onReplaceState={setState} />}

          {section === "WORKOUT" && (
            <WorkoutMusicView
              state={state}
              onSaveState={saveState}
              music={musicController}
              onReloadState={loadAll}
            />
          )}

          {section === "NOTES" && <NotesView state={state} onSaveState={saveState} />}

          {section === "MONEY" && <MoneyView state={state} onSaveState={saveState} />}

          {section === "VAULT" && <VaultView />}

          {section === "FOCUS" && <FocusView state={state} onSaveState={saveState} />}

          {section === "SUPPORT" && (
            <SupportView state={state} onSaveState={saveState} onReloadState={loadAll} />
          )}

          {section === "SETTINGS" && (
            <SettingsView
              state={state}
              onSaveState={saveState}
              pairInfo={pairInfo}
              onOpenPair={() => setPairModalOpen(true)}
            />
          )}
        </main>

        {/* Global Mini Music Bar when playing on other tabs */}
        {currentTrack && section !== "WORKOUT" && (
          <div className="sticky bottom-3 px-4 max-w-2xl w-full mx-auto z-30">
            <div className="vk-card p-2.5 px-4 shadow-lg flex items-center gap-3">
              <Headphones className="w-4 h-4 shrink-0" style={{ color: "var(--vk-accent)" }} />
              <button
                onClick={() => setSection("WORKOUT")}
                className="flex-1 min-w-0 text-left bg-transparent border-0 cursor-pointer p-0"
              >
                <div className="text-xs font-semibold truncate">{currentTrack.title}</div>
                <div className="text-[11px] truncate" style={{ color: "var(--vk-ink-2)" }}>
                  {currentTrack.artist || "Workout Music"}
                </div>
              </button>
              <button
                onClick={musicController.togglePlay}
                className="w-8 h-8 rounded-full bg-[#EAFF87] text-[#1B201C] flex items-center justify-center border-0 cursor-pointer"
              >
                {playing ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
              </button>
              <button onClick={nextTrack} className="p-1.5 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer">
                <SkipForward className="w-4 h-4" />
              </button>
              <button onClick={musicController.stop} className="p-1.5 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer">
                <X className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Modal QR Pairing overlay */}
      {pairModalOpen && (
        <PhonePairGate
          pairInfo={pairInfo}
          isModal
          onClose={() => setPairModalOpen(false)}
          onRefreshQr={async () => {
            await api.pairRefresh();
            await loadAll();
          }}
          onUnlink={async () => {
            await api.pairUnlink();
            await loadAll();
          }}
          onContinueStandalone={() => setPairModalOpen(false)}
        />
      )}
    </div>
  );
}
