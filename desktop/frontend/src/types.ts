export type Priority = "HIGH" | "MEDIUM" | "LOW";
export type ReminderRepeat = "NONE" | "DAILY" | "WEEKLY" | "MONTHLY";
export type TxType = "EXPENSE" | "INCOME";
export type SupportCategory = "BUG" | "FEATURE" | "AI_VOICE" | "WORKOUT_MUSIC" | "DATA_VAULT" | "OTHER";
export type SupportPriority = "LOW" | "NORMAL" | "URGENT";
export type SupportTicketStatus = "SENT" | "DRAFT" | "RESOLVED";

export interface Card {
  id: string;
  text: string;
  priority: Priority;
  notes?: string | null;
  tags: string[];
  dueAt?: number | null;
  remindBeforeMin?: number | null;
  createdAt: number;
  updatedAt: number;
}

export interface Stage {
  id: string;
  title: string;
  color: string;
  cards: Card[];
}

export interface Board {
  id: string;
  title: string;
  stages: Stage[];
  createdAt: number;
}

export interface Workspace {
  boards: Board[];
  activeBoardId: string;
}

export interface CalendarReminder {
  id: string;
  title: string;
  notes: string;
  at: number;
  remindBeforeMin: number;
  repeat: ReminderRepeat;
  done: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface Note {
  id: string;
  title: string;
  body: string;
  color: number;
  pinned: boolean;
  source: string;
  createdAt: number;
  updatedAt: number;
}

export interface Transaction {
  id: string;
  amount: number;
  type: TxType;
  category: string;
  note: string;
  at: number;
  createdAt: number;
}

export interface Budget {
  category: string;
  limit: number;
}

export interface WorkoutType {
  id: string;
  name: string;
  emoji: string;
  gps: boolean;
  steps: boolean;
  met: number;
  goalMin: number;
  builtIn: boolean;
}

export interface WorkoutSession {
  id: string;
  typeId: string;
  name: string;
  emoji: string;
  startedAt: number;
  durationSec: number;
  distanceM: number;
  steps: number;
  calories: number;
}

export interface WorkoutTrack {
  videoId: string;
  title: string;
  artist: string;
  album: string;
  durationSec: number;
  thumbnailUrl: string;
}

export interface DownloadedTrack {
  track: WorkoutTrack;
  localPath: string;
  mimeType: string;
  sizeBytes: number;
  downloadedAt: number;
  exportedToMusicFolder: boolean;
}

export interface FocusSession {
  id: string;
  startedAt: number;
  plannedMin: number;
  actualSec: number;
  completed: boolean;
}

export interface ChatMessage {
  id: string;
  fromUser: boolean;
  text: string;
  createdAt: number;
  changes: string[];
  failures: string[];
  engine?: string | null;
  isError: boolean;
  fromVoice: boolean;
}

export interface ChatSession {
  id: string;
  title: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
}

export interface PasswordEntry {
  id: string;
  title: string;
  username: string;
  password: string;
  url: string;
  notes: string;
  createdAt: number;
  updatedAt: number;
}

export interface SupportTicket {
  id: string;
  category: SupportCategory;
  priority: SupportPriority;
  subject: string;
  description: string;
  contactEmail: string;
  includeDiagnostics: boolean;
  status: SupportTicketStatus;
  createdAt: number;
  updatedAt: number;
}

export interface Settings {
  onboarded: boolean;
  userName: string;
  language: string;
  engine: string;
  themeMode: string;
  cloudModels: Record<string, string>;
  currency: string;
  weightKg: number;
  autoCheckUpdates: boolean;
  dismissedUpdateTag: string;
  speakReplies: boolean;
}

export interface AppState {
  workspace: Workspace;
  settings: Settings;
  sessions: ChatSession[];
  activeSessionId?: string | null;
  voiceChat: ChatMessage[];
  notes: Note[];
  transactions: Transaction[];
  budgets: Budget[];
  workoutTypes: WorkoutType[];
  workouts: WorkoutSession[];
  focusSessions: FocusSession[];
  calendarReminders: CalendarReminder[];
  savedWorkoutTracks: WorkoutTrack[];
  downloadedWorkoutTracks: DownloadedTrack[];
  supportTickets: SupportTicket[];
}

export interface PairedPhone {
  deviceName: string;
  appVersion: string;
  token: string;
  pairedAt: number;
  lastSyncedAt: number;
}

export interface PairingSessionInfo {
  host: string;
  port: number;
  token: string;
  code: string;
  pcName: string;
  qrUrl: string;
  qrSvg: string;
  githubReleasesUrl: string;
}

export interface PairInfoResponse {
  paired: boolean;
  pairedPhone: PairedPhone | null;
  session: PairingSessionInfo;
  githubReleasesUrl: string;
}

export interface ReleaseInfo {
  currentVersion: string;
  latestTag: string;
  latestVersion: string;
  updateAvailable: boolean;
  title: string;
  notes: string;
  htmlUrl: string;
  apkDownloadUrl?: string | null;
  windowsDownloadUrl?: string | null;
}

export const NOTE_COLORS: Array<{ name: string; num: number; hex: string }> = [
  { name: "Lime", num: 0xffEAFF87, hex: "#EAFF87" },
  { name: "Mint", num: 0xffC8F2DC, hex: "#C8F2DC" },
  { name: "Sky", num: 0xffCFE6FF, hex: "#CFE6FF" },
  { name: "Lavender", num: 0xffE3DAFF, hex: "#E3DAFF" },
  { name: "Peach", num: 0xffFFDCC7, hex: "#FFDCC7" },
  { name: "Rose", num: 0xffFFD6E2, hex: "#FFD6E2" },
  { name: "Sand", num: 0xffF4EBD0, hex: "#F4EBD0" },
  { name: "Cloud", num: 0xffFFFFFF, hex: "#FFFFFF" },
];

export function noteColorHex(num: number): string {
  const found = NOTE_COLORS.find((c) => c.num === num);
  if (found) return found.hex;
  return "#" + (num & 0xffffff).toString(16).padStart(6, "0");
}

export function newId(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.floor(Math.random() * 0xfffff).toString(36)}`;
}

export function formatDuration(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, "0")}:${String(r).padStart(2, "0")}`;
  return `${m}:${String(r).padStart(2, "0")}`;
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}
