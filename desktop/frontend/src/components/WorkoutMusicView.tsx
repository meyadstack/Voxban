import { useEffect, useState } from "react";
import {
  CheckCircle2,
  Download,
  FolderOpen,
  Heart,
  Loader2,
  Music,
  Pause,
  Play,
  Search,
  SkipBack,
  SkipForward,
  Square,
  Trash2,
} from "lucide-react";
import { api } from "../api";
import type { AppState, WorkoutTrack } from "../types";
import { formatBytes, formatDuration, newId } from "../types";

const VIBES = [
  { emoji: "🔥", label: "HIIT Energy", q: "high energy hiit workout music mix" },
  { emoji: "🏃", label: "Running", q: "running tempo upbeat workout songs" },
  { emoji: "🏋️", label: "Gym Pump", q: "gym motivation bass workout music" },
  { emoji: "🚴", label: "Cycling", q: "cycling cardio electronic workout music" },
  { emoji: "🧘", label: "Yoga & Flow", q: "calm yoga stretching ambient music" },
];

export interface MusicController {
  current: WorkoutTrack | null;
  queue: WorkoutTrack[];
  playing: boolean;
  loading: boolean;
  error: string | null;
  positionSec: number;
  durationSec: number;
  playTrack: (t: WorkoutTrack, list?: WorkoutTrack[]) => void;
  togglePlay: () => void;
  next: () => void;
  prev: () => void;
  seek: (sec: number) => void;
  stop: () => void;
}

interface Props {
  state: AppState;
  onSaveState: (next: AppState) => void;
  music: MusicController;
  onReloadState: () => Promise<void>;
}

export function WorkoutMusicView({ state, onSaveState, music, onReloadState }: Props) {
  // Workout live timer
  const [activeTypeId, setActiveTypeId] = useState<string | null>(null);
  const [elapsedSec, setElapsedSec] = useState(0);
  const [timerPaused, setTimerPaused] = useState(false);

  // Music browser
  const [tab, setTab] = useState<"SEARCH" | "DOWNLOADS" | "SAVED">("SEARCH");
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<WorkoutTrack[]>([]);
  const [searching, setSearching] = useState(false);
  const [downloadingIds, setDownloadingIds] = useState<Record<string, boolean>>({});
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!activeTypeId || timerPaused) return;
    const id = setInterval(() => setElapsedSec((s) => s + 1), 1000);
    return () => clearInterval(id);
  }, [activeTypeId, timerPaused]);

  const activeType = state.workoutTypes.find((w) => w.id === activeTypeId);

  const finishWorkout = () => {
    if (!activeType) return;
    const kcal = Math.round(((activeType.met * state.settings.weightKg * elapsedSec) / 3600) * 10) / 10;
    onSaveState({
      ...state,
      workouts: [
        {
          id: newId("wo"),
          typeId: activeType.id,
          name: activeType.name,
          emoji: activeType.emoji,
          startedAt: Date.now() - elapsedSec * 1000,
          durationSec: elapsedSec,
          distanceM: 0,
          steps: 0,
          calories: kcal,
        },
        ...state.workouts,
      ],
    });
    setActiveTypeId(null);
    setElapsedSec(0);
    setTimerPaused(false);
  };

  const runSearch = async (q: string) => {
    const clean = q.trim();
    if (!clean) return;
    setQuery(clean);
    setTab("SEARCH");
    setSearching(true);
    setNotice(null);
    try {
      const res = await api.searchMusic(clean);
      setResults(res.tracks || []);
      if (!res.tracks?.length) setNotice(`No songs found for “${clean}”.`);
    } catch (e) {
      setNotice(`Search failed: ${String(e)}`);
    } finally {
      setSearching(false);
    }
  };

  const downloadTrack = async (track: WorkoutTrack) => {
    if (downloadingIds[track.videoId]) return;
    setDownloadingIds((m) => ({ ...m, [track.videoId]: true }));
    setNotice(`Downloading “${track.title}” to your PC Music/Voxban folder…`);
    try {
      const res = await api.downloadMusic(track);
      if (res.ok && res.downloaded) {
        await onReloadState();
        setNotice(
          `Downloaded “${track.title}” (${formatBytes(res.downloaded.sizeBytes)}) into ${res.folder || "Music/Voxban"}`,
        );
      } else {
        setNotice(`Download failed: ${res.error || "Unknown error"}`);
      }
    } catch (e) {
      setNotice(`Download failed: ${String(e)}`);
    } finally {
      setDownloadingIds((m) => {
        const copy = { ...m };
        delete copy[track.videoId];
        return copy;
      });
    }
  };

  const toggleFav = (track: WorkoutTrack) => {
    const exists = state.savedWorkoutTracks.some((t) => t.videoId === track.videoId);
    const next = exists
      ? state.savedWorkoutTracks.filter((t) => t.videoId !== track.videoId)
      : [track, ...state.savedWorkoutTracks];
    onSaveState({ ...state, savedWorkoutTracks: next });
  };

  const downloadedIds = new Set(state.downloadedWorkoutTracks.map((d) => d.track.videoId));

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Workouts &amp; Music</h2>
        <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
          Stream workout music with 150 MB Rust disk caching · Download songs offline to your PC Music folder
        </p>
      </div>

      {/* Live workout timer banner when running */}
      {activeType && (
        <div
          className="rounded-3xl p-6 flex flex-wrap items-center justify-between gap-4"
          style={{ background: "#EAFF87", color: "#1B201C" }}
        >
          <div>
            <div className="text-xs font-bold uppercase tracking-wider opacity-75">
              {activeType.emoji} {activeType.name} · LIVE
            </div>
            <div className="text-4xl font-bold font-mono mt-1">{formatDuration(elapsedSec)}</div>
            <div className="text-xs opacity-75 mt-1">
              Goal: {activeType.goalMin} min · Est.{" "}
              {Math.round((activeType.met * state.settings.weightKg * elapsedSec) / 3600)} kcal
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setTimerPaused((p) => !p)}
              className="px-4 py-2.5 rounded-full bg-white text-[#1B201C] font-semibold text-sm border-0 cursor-pointer flex items-center gap-1.5"
            >
              {timerPaused ? <Play className="w-4 h-4" /> : <Pause className="w-4 h-4" />}
              {timerPaused ? "Resume" : "Pause"}
            </button>
            <button
              onClick={finishWorkout}
              className="px-4 py-2.5 rounded-full bg-[#1B201C] text-[#EAFF87] font-semibold text-sm border-0 cursor-pointer flex items-center gap-1.5"
            >
              <Square className="w-4 h-4" /> Finish
            </button>
          </div>
        </div>
      )}

      {/* Music Player + Downloader Card */}
      <div className="vk-card p-5 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-[#EAFF87] text-[#1B201C] flex items-center justify-center">
              <Music className="w-5 h-5" />
            </div>
            <div>
              <h3 className="font-semibold text-base">Workout Music Player &amp; Downloader</h3>
              <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                Cached audio · Offline downloads saved to Music/Voxban
              </p>
            </div>
          </div>
          <div className="flex items-center gap-1.5">
            <button
              onClick={() => setTab("SEARCH")}
              className={`px-3 py-1.5 text-xs rounded-full cursor-pointer border ${
                tab === "SEARCH" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
              }`}
            >
              Search
            </button>
            <button
              onClick={() => setTab("DOWNLOADS")}
              className={`px-3 py-1.5 text-xs rounded-full cursor-pointer border ${
                tab === "DOWNLOADS" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
              }`}
            >
              Downloaded ({state.downloadedWorkoutTracks.length})
            </button>
            <button
              onClick={() => setTab("SAVED")}
              className={`px-3 py-1.5 text-xs rounded-full cursor-pointer border ${
                tab === "SAVED" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
              }`}
            >
              Saved ({state.savedWorkoutTracks.length})
            </button>
          </div>
        </div>

        {/* Now Playing Strip */}
        {music.current && (
          <div className="p-4 rounded-2xl space-y-3" style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}>
            <div className="flex items-center gap-3">
              {music.current.thumbnailUrl ? (
                <img src={music.current.thumbnailUrl} alt="" className="w-14 h-14 rounded-xl object-cover shrink-0" />
              ) : (
                <div className="w-14 h-14 rounded-xl vk-well flex items-center justify-center shrink-0">
                  <Music className="w-6 h-6" />
                </div>
              )}
              <div className="flex-1 min-w-0">
                <div className="font-semibold text-sm truncate">{music.current.title}</div>
                <div className="text-xs truncate" style={{ color: "var(--vk-ink-2)" }}>
                  {music.current.artist || "Workout Music"}
                </div>
              </div>
              <button
                onClick={() => void downloadTrack(music.current!)}
                disabled={!!downloadingIds[music.current.videoId]}
                className="vk-btn-outline px-3 py-1.5 text-xs flex items-center gap-1.5 cursor-pointer"
                title="Download offline to PC"
              >
                {downloadingIds[music.current.videoId] ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : downloadedIds.has(music.current.videoId) ? (
                  <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600" />
                ) : (
                  <Download className="w-3.5 h-3.5" />
                )}
                {downloadedIds.has(music.current.videoId) ? "Downloaded" : "Download"}
              </button>
            </div>
            <div className="flex items-center gap-3">
              <span className="text-[11px] font-mono w-10" style={{ color: "var(--vk-ink-3)" }}>
                {formatDuration(music.positionSec)}
              </span>
              <input
                type="range"
                min={0}
                max={Math.max(1, music.durationSec || music.current.durationSec || 100)}
                value={music.positionSec}
                onChange={(e) => music.seek(Number(e.target.value))}
                className="flex-1 accent-[#204B37]"
              />
              <span className="text-[11px] font-mono w-10 text-right" style={{ color: "var(--vk-ink-3)" }}>
                {formatDuration(music.durationSec || music.current.durationSec)}
              </span>
            </div>
            <div className="flex items-center justify-center gap-4">
              <button onClick={music.prev} className="p-2 rounded-full hover:bg-black/5 bg-transparent border-0 cursor-pointer">
                <SkipBack className="w-5 h-5" />
              </button>
              <button
                onClick={music.togglePlay}
                className="w-11 h-11 rounded-full bg-[#1B201C] text-[#EAFF87] flex items-center justify-center border-0 cursor-pointer"
              >
                {music.loading ? (
                  <Loader2 className="w-5 h-5 animate-spin" />
                ) : music.playing ? (
                  <Pause className="w-5 h-5" />
                ) : (
                  <Play className="w-5 h-5" />
                )}
              </button>
              <button onClick={music.next} className="p-2 rounded-full hover:bg-black/5 bg-transparent border-0 cursor-pointer">
                <SkipForward className="w-5 h-5" />
              </button>
            </div>
            {music.error && (
              <div className="text-xs text-center" style={{ color: "var(--vk-danger)" }}>
                {music.error}
              </div>
            )}
          </div>
        )}

        {notice && (
          <div className="vk-well px-3.5 py-2 text-xs flex items-center justify-between gap-2">
            <span>{notice}</span>
            <button onClick={() => setNotice(null)} className="text-xs underline bg-transparent border-0 cursor-pointer">
              Dismiss
            </button>
          </div>
        )}

        {tab === "SEARCH" && (
          <div className="space-y-3">
            <div className="flex gap-2">
              <div className="vk-well flex-1 px-3.5 py-2 flex items-center gap-2">
                <Search className="w-4 h-4" style={{ color: "var(--vk-ink-3)" }} />
                <input
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") void runSearch(query);
                  }}
                  placeholder="Search songs, artists, or workout mixes…"
                  className="bg-transparent border-0 outline-none text-sm flex-1"
                />
              </div>
              <button
                onClick={() => void runSearch(query)}
                disabled={searching || !query.trim()}
                className="vk-btn-lime px-5 py-2 text-xs cursor-pointer disabled:opacity-40"
              >
                {searching ? "Searching…" : "Search"}
              </button>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {VIBES.map((v) => (
                <button
                  key={v.label}
                  onClick={() => void runSearch(v.q)}
                  className="vk-btn-outline px-3 py-1 text-xs cursor-pointer"
                >
                  {v.emoji} {v.label}
                </button>
              ))}
            </div>
            <div className="space-y-1.5 max-h-80 overflow-y-auto">
              {results.map((t) => {
                const isCur = music.current?.videoId === t.videoId;
                const isFav = state.savedWorkoutTracks.some((s) => s.videoId === t.videoId);
                const isDl = downloadedIds.has(t.videoId);
                const isBusy = !!downloadingIds[t.videoId];
                return (
                  <div
                    key={t.videoId}
                    className="p-2.5 rounded-2xl flex items-center gap-3 transition-colors"
                    style={{
                      background: isCur ? "#EAFF87" : "var(--vk-surface)",
                      color: isCur ? "#1B201C" : "var(--vk-ink)",
                      border: "1px solid var(--vk-border)",
                    }}
                  >
                    <button
                      onClick={() => music.playTrack(t, results)}
                      className="flex items-center gap-3 flex-1 min-w-0 text-left bg-transparent border-0 cursor-pointer p-0"
                    >
                      {t.thumbnailUrl ? (
                        <img src={t.thumbnailUrl} alt="" className="w-10 h-10 rounded-lg object-cover shrink-0" />
                      ) : (
                        <div className="w-10 h-10 rounded-lg vk-well flex items-center justify-center shrink-0">
                          <Music className="w-4 h-4" />
                        </div>
                      )}
                      <div className="min-w-0">
                        <div className="text-sm font-medium truncate">{t.title}</div>
                        <div className="text-xs opacity-70 truncate">
                          {t.artist || "Music"} {t.durationSec > 0 ? `· ${formatDuration(t.durationSec)}` : ""}
                        </div>
                      </div>
                    </button>
                    <button
                      onClick={() => void downloadTrack(t)}
                      disabled={isBusy}
                      className="p-2 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer"
                      title={isDl ? "Downloaded offline" : "Download song to PC"}
                    >
                      {isBusy ? (
                        <Loader2 className="w-4 h-4 animate-spin" />
                      ) : isDl ? (
                        <CheckCircle2 className="w-4 h-4 text-emerald-700" />
                      ) : (
                        <Download className="w-4 h-4" />
                      )}
                    </button>
                    <button
                      onClick={() => toggleFav(t)}
                      className="p-2 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer"
                      title="Save favourite"
                    >
                      <Heart className={`w-4 h-4 ${isFav ? "fill-red-500 text-red-500" : ""}`} />
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {tab === "DOWNLOADS" && (
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium" style={{ color: "var(--vk-ink-2)" }}>
                {state.downloadedWorkoutTracks.length} offline tracks saved on your PC
              </span>
              <button
                onClick={() => void api.openMusicFolder()}
                className="vk-btn-outline px-3 py-1 text-xs inline-flex items-center gap-1.5 cursor-pointer"
              >
                <FolderOpen className="w-3.5 h-3.5" /> Open Music/Voxban folder
              </button>
            </div>
            {state.downloadedWorkoutTracks.length === 0 ? (
              <p className="text-xs py-6 text-center" style={{ color: "var(--vk-ink-3)" }}>
                No downloaded songs yet. Search any track and click the Download button to save it offline.
              </p>
            ) : (
              <div className="space-y-2">
                {state.downloadedWorkoutTracks.map((d) => (
                  <div
                    key={d.track.videoId}
                    className="p-3 rounded-2xl flex items-center justify-between gap-3"
                    style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}
                  >
                    <button
                      onClick={() =>
                        music.playTrack(
                          d.track,
                          state.downloadedWorkoutTracks.map((x) => x.track),
                        )
                      }
                      className="flex items-center gap-3 flex-1 min-w-0 text-left bg-transparent border-0 cursor-pointer p-0"
                    >
                      <Play className="w-4 h-4 shrink-0" />
                      <div className="min-w-0">
                        <div className="text-sm font-medium truncate">{d.track.title}</div>
                        <div className="text-xs truncate" style={{ color: "var(--vk-ink-2)" }}>
                          {d.track.artist} · {formatBytes(d.sizeBytes)} · Offline
                        </div>
                      </div>
                    </button>
                    <a
                      href={api.downloadFileUrl(d.track.videoId)}
                      download
                      className="p-2 rounded-full hover:bg-black/10"
                      title="Save another copy"
                    >
                      <Download className="w-4 h-4" />
                    </a>
                    <button
                      onClick={async () => {
                        await api.deleteDownload(d.track.videoId);
                        await onReloadState();
                      }}
                      className="p-2 rounded-full hover:bg-red-500/15 bg-transparent border-0 cursor-pointer"
                      title="Delete offline file"
                    >
                      <Trash2 className="w-4 h-4" style={{ color: "var(--vk-danger)" }} />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {tab === "SAVED" && (
          <div className="space-y-2">
            {state.savedWorkoutTracks.length === 0 ? (
              <p className="text-xs py-6 text-center" style={{ color: "var(--vk-ink-3)" }}>
                No saved favourites yet.
              </p>
            ) : (
              state.savedWorkoutTracks.map((t) => (
                <div
                  key={t.videoId}
                  className="p-3 rounded-2xl flex items-center justify-between gap-3"
                  style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}
                >
                  <button
                    onClick={() => music.playTrack(t, state.savedWorkoutTracks)}
                    className="flex items-center gap-3 flex-1 min-w-0 text-left bg-transparent border-0 cursor-pointer p-0"
                  >
                    <Play className="w-4 h-4 shrink-0" />
                    <div className="min-w-0">
                      <div className="text-sm font-medium truncate">{t.title}</div>
                      <div className="text-xs truncate" style={{ color: "var(--vk-ink-2)" }}>
                        {t.artist}
                      </div>
                    </div>
                  </button>
                  <button
                    onClick={() => void downloadTrack(t)}
                    className="p-2 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer"
                    title="Download offline"
                  >
                    <Download className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => toggleFav(t)}
                    className="p-2 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer"
                  >
                    <Heart className="w-4 h-4 fill-red-500 text-red-500" />
                  </button>
                </div>
              ))
            )}
          </div>
        )}
      </div>

      {/* Start a workout cards */}
      <div>
        <h3 className="font-semibold text-sm mb-3">Start a workout</h3>
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
          {state.workoutTypes.map((w) => (
            <button
              key={w.id}
              onClick={() => {
                setActiveTypeId(w.id);
                setElapsedSec(0);
                setTimerPaused(false);
              }}
              className="vk-card p-4 text-left hover:scale-[1.01] transition-transform cursor-pointer"
            >
              <div className="text-2xl">{w.emoji}</div>
              <div className="font-semibold text-sm mt-2">{w.name}</div>
              <div className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                {w.goalMin} min goal
              </div>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
