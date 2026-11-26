import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Calendar,
  Check,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Copy,
  Eye,
  EyeOff,
  Fingerprint,
  Flame,
  KeyRound,
  Lock,
  Plus,
  QrCode,
  RefreshCw,
  ShieldCheck,
  Timer,
  Trash2,
  Unlock,
  Wallet,
  X,
} from "lucide-react";
import { api, type BiometricStatus } from "../api";
import type { AppState, PasswordEntry, Transaction, TxType, VaultShareCreateResponse } from "../types";
import { formatDuration, newId } from "../types";

const EXPENSE_CATS = ["Food", "Groceries", "Transport", "Shopping", "Bills", "Rent", "Health", "Education", "Other"];
const INCOME_CATS = ["Salary", "Business", "Freelance", "Investment", "Gift", "Other"];

function dayKey(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export function MoneyView({ state, onSaveState }: { state: AppState; onSaveState: (n: AppState) => void }) {
  const sym = state.settings.currency || "$";
  const now = new Date();
  const [viewMode, setViewMode] = useState<"overview" | "calendar">("overview");
  const [calYear, setCalYear] = useState(now.getFullYear());
  const [calMonth, setCalMonth] = useState(now.getMonth()); // 0..11
  const [selectedDay, setSelectedDay] = useState<string | null>(null);

  const [type, setType] = useState<TxType>("EXPENSE");
  const [amount, setAmount] = useState("");
  const [category, setCategory] = useState("Food");
  const [note, setNote] = useState("");

  const monthTransactions = useMemo(
    () =>
      state.transactions.filter((t) => {
        const d = new Date(t.at);
        return d.getFullYear() === calYear && d.getMonth() === calMonth;
      }),
    [state.transactions, calYear, calMonth],
  );

  const byDay = useMemo(() => {
    const map: Record<string, Transaction[]> = {};
    for (const t of monthTransactions) {
      const k = dayKey(t.at);
      (map[k] ||= []).push(t);
    }
    return map;
  }, [monthTransactions]);

  const visibleTx = selectedDay ? byDay[selectedDay] || [] : monthTransactions;
  const income = monthTransactions.filter((t) => t.type === "INCOME").reduce((s, t) => s + t.amount, 0);
  const expense = monthTransactions.filter((t) => t.type === "EXPENSE").reduce((s, t) => s + t.amount, 0);

  const shiftMonth = (delta: number) => {
    const d = new Date(calYear, calMonth + delta, 1);
    setCalYear(d.getFullYear());
    setCalMonth(d.getMonth());
    setSelectedDay(null);
  };

  const monthTitle = new Date(calYear, calMonth, 1).toLocaleDateString(undefined, { month: "long", year: "numeric" });
  const daysInMonth = new Date(calYear, calMonth + 1, 0).getDate();
  const firstDow = (new Date(calYear, calMonth, 1).getDay() + 6) % 7; // Monday = 0
  const todayKey = dayKey(Date.now());

  const addTx = () => {
    const val = parseFloat(amount);
    if (!val || val <= 0) return;
    const nowMs = Date.now();
    let atMs = nowMs;
    if (selectedDay) {
      const [y, m, dd] = selectedDay.split("-").map(Number);
      const cur = new Date();
      atMs = new Date(y, m - 1, dd, cur.getHours(), cur.getMinutes()).getTime();
    }
    const tx: Transaction = {
      id: newId("tx"),
      amount: val,
      type,
      category,
      note: note.trim(),
      at: atMs,
      createdAt: nowMs,
    };
    onSaveState({ ...state, transactions: [tx, ...state.transactions] });
    setAmount("");
    setNote("");
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <h2 className="text-2xl font-bold tracking-tight">Money Manager</h2>
          <div className="flex items-center gap-1 vk-well px-2 py-1">
            <button onClick={() => shiftMonth(-1)} className="p-1 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer">
              <ChevronLeft className="w-4 h-4" />
            </button>
            <span className="text-xs font-semibold px-2 min-w-[120px] text-center">{monthTitle}</span>
            <button onClick={() => shiftMonth(1)} className="p-1 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer">
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        </div>
        <div className="flex gap-1.5">
          <button
            onClick={() => {
              setViewMode("overview");
              setSelectedDay(null);
            }}
            className={`px-3.5 py-1.5 text-xs rounded-full cursor-pointer border inline-flex items-center gap-1.5 ${
              viewMode === "overview" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
            }`}
          >
            <Wallet className="w-3.5 h-3.5" /> Overview
          </button>
          <button
            onClick={() => setViewMode("calendar")}
            className={`px-3.5 py-1.5 text-xs rounded-full cursor-pointer border inline-flex items-center gap-1.5 ${
              viewMode === "calendar" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
            }`}
          >
            <Calendar className="w-3.5 h-3.5" /> Calendar
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div className="vk-card p-5">
          <div className="text-xs" style={{ color: "var(--vk-ink-2)" }}>Balance ({monthTitle})</div>
          <div className="text-2xl font-bold mt-1">{sym}{(income - expense).toLocaleString()}</div>
        </div>
        <div className="vk-card p-5">
          <div className="text-xs" style={{ color: "var(--vk-low)" }}>Income</div>
          <div className="text-2xl font-bold mt-1" style={{ color: "var(--vk-low)" }}>
            +{sym}{income.toLocaleString()}
          </div>
        </div>
        <div className="vk-card p-5">
          <div className="text-xs" style={{ color: "var(--vk-danger)" }}>Expenses</div>
          <div className="text-2xl font-bold mt-1" style={{ color: "var(--vk-danger)" }}>
            -{sym}{expense.toLocaleString()}
          </div>
        </div>
      </div>

      {/* Monthly Money Calendar */}
      {viewMode === "calendar" && (
        <div className="vk-card p-5 space-y-3">
          <div className="flex items-center justify-between">
            <div className="text-sm font-semibold">Daily Income &amp; Expense Calendar</div>
            {selectedDay && (
              <button onClick={() => setSelectedDay(null)} className="vk-btn-outline px-3 py-1 text-xs cursor-pointer">
                Show full month
              </button>
            )}
          </div>
          <div className="grid grid-cols-7 gap-1.5 text-center text-[11px] font-semibold" style={{ color: "var(--vk-ink-3)" }}>
            {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((d) => (
              <div key={d} className="py-1">{d}</div>
            ))}
          </div>
          <div className="grid grid-cols-7 gap-1.5">
            {Array.from({ length: firstDow }).map((_, i) => (
              <div key={`pad-${i}`} />
            ))}
            {Array.from({ length: daysInMonth }).map((_, idx) => {
              const day = idx + 1;
              const k = `${calYear}-${String(calMonth + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
              const txs = byDay[k] || [];
              const dInc = txs.filter((t) => t.type === "INCOME").reduce((s, t) => s + t.amount, 0);
              const dExp = txs.filter((t) => t.type === "EXPENSE").reduce((s, t) => s + t.amount, 0);
              const isSel = selectedDay === k;
              const isToday = todayKey === k;
              return (
                <button
                  key={k}
                  onClick={() => setSelectedDay(isSel ? null : k)}
                  className="p-2 rounded-2xl min-h-[68px] flex flex-col items-start justify-between text-left cursor-pointer transition-all"
                  style={{
                    background: isSel ? "#EAFF87" : txs.length > 0 ? "var(--vk-surface)" : "transparent",
                    color: isSel ? "#1B201C" : "var(--vk-ink)",
                    border: isToday && !isSel ? "1.5px solid var(--vk-ink)" : "1px solid var(--vk-border)",
                  }}
                >
                  <span className="text-xs font-semibold">{day}</span>
                  {txs.length > 0 && (
                    <div className="w-full space-y-0.5 text-[10px] font-medium truncate">
                      {dInc > 0 && (
                        <div style={{ color: isSel ? "#1B201C" : "var(--vk-low)" }}>+{sym}{dInc.toLocaleString()}</div>
                      )}
                      {dExp > 0 && (
                        <div style={{ color: isSel ? "#1B201C" : "var(--vk-danger)" }}>-{sym}{dExp.toLocaleString()}</div>
                      )}
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {/* Add transaction */}
      <div className="vk-card p-5 space-y-3">
        {selectedDay && (
          <div className="text-xs font-medium" style={{ color: "var(--vk-ink-2)" }}>
            Logging for selected date: <b>{selectedDay}</b>
          </div>
        )}
        <div className="flex flex-wrap gap-2 items-center">
          {(["EXPENSE", "INCOME"] as TxType[]).map((t) => (
            <button
              key={t}
              onClick={() => {
                setType(t);
                setCategory(t === "INCOME" ? "Salary" : "Food");
              }}
              className={`px-4 py-1.5 text-xs rounded-full cursor-pointer border ${
                type === t ? "vk-btn-lime border-transparent" : "vk-btn-outline"
              }`}
            >
              {t === "EXPENSE" ? "Expense" : "Income"}
            </button>
          ))}
          <input
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder={`Amount (${sym})`}
            className="vk-well px-3.5 py-2 text-sm border-0 outline-none w-36"
          />
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            className="vk-well px-3.5 py-2 text-sm border-0 outline-none"
          >
            {(type === "INCOME" ? INCOME_CATS : EXPENSE_CATS).map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") addTx();
            }}
            placeholder="Note (optional)"
            className="vk-well px-3.5 py-2 text-sm border-0 outline-none flex-1 min-w-[160px]"
          />
          <button onClick={addTx} className="vk-btn-lime px-5 py-2 text-xs cursor-pointer inline-flex items-center gap-1">
            <Plus className="w-4 h-4" /> Log
          </button>
        </div>
      </div>

      {/* List */}
      <div className="vk-card p-4 space-y-2">
        {visibleTx.length === 0 ? (
          <p className="text-xs py-6 text-center" style={{ color: "var(--vk-ink-3)" }}>
            {selectedDay ? `No transactions on ${selectedDay}.` : `No transactions in ${monthTitle} yet.`}
          </p>
        ) : (
          visibleTx.map((t) => (
            <div
              key={t.id}
              className="p-3 rounded-2xl flex items-center justify-between gap-2"
              style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}
            >
              <div>
                <div className="text-sm font-medium">{t.category}</div>
                <div className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                  {t.note ? `${t.note} · ` : ""}{new Date(t.at).toLocaleDateString()}
                </div>
              </div>
              <div className="flex items-center gap-3">
                <span
                  className="font-semibold text-sm"
                  style={{ color: t.type === "INCOME" ? "var(--vk-low)" : "var(--vk-danger)" }}
                >
                  {t.type === "INCOME" ? "+" : "-"}{sym}{t.amount.toLocaleString()}
                </span>
                <button
                  onClick={() => onSaveState({ ...state, transactions: state.transactions.filter((x) => x.id !== t.id) })}
                  className="p-1.5 rounded-full hover:bg-red-500/15 bg-transparent border-0 cursor-pointer"
                >
                  <Trash2 className="w-4 h-4" style={{ color: "var(--vk-danger)" }} />
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

export function VaultView() {
  const [exists, setExists] = useState<boolean | null>(null);
  const [biometric, setBiometric] = useState<BiometricStatus>({
    available: false,
    enrolled: false,
    label: "Fingerprint",
  });
  const [enrollOnUnlock, setEnrollOnUnlock] = useState(true);
  const [bioBusy, setBioBusy] = useState(false);
  const [bioNotice, setBioNotice] = useState<string | null>(null);
  const [justLocked, setJustLocked] = useState(false);
  const autoPromptedRef = useRef(false);
  const [passphrase, setPassphrase] = useState("");
  const [unlocked, setUnlocked] = useState(false);
  const [entries, setEntries] = useState<PasswordEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // One-time QR sharing modals
  const [sharingEntry, setSharingEntry] = useState<PasswordEntry | null>(null);
  const [shareMode, setShareMode] = useState<"lan" | "direct">("lan");
  const [shareTtl, setShareTtl] = useState<number>(120);
  const [shareData, setShareData] = useState<VaultShareCreateResponse | null>(null);
  const [shareBurned, setShareBurned] = useState(false);
  const [shareClaimed, setShareClaimed] = useState(false);
  const [shareRemaining, setShareRemaining] = useState(120);
  const [importOpen, setImportOpen] = useState(false);
  const [importCode, setImportCode] = useState("");
  const [importBusy, setImportBusy] = useState(false);
  const [importErr, setImportErr] = useState<string | null>(null);

  const [title, setTitle] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [url, setUrl] = useState("");

  const unlockWithBiometric = useCallback(async (labelFallback?: string) => {
    setError(null);
    setBioNotice(null);
    setBioBusy(true);
    try {
      const res = await api.vaultBiometricUnlock();
      if (res.ok) {
        setEntries(res.entries || []);
        setUnlocked(true);
        setPassphrase("");
        setJustLocked(false);
        if (res.biometric) setBiometric(res.biometric);
      } else {
        if (res.biometric) setBiometric(res.biometric);
        setError(res.error || `${labelFallback || "Biometric"} unlock cancelled. Enter your master password below.`);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBioBusy(false);
    }
  }, []);

  useEffect(() => {
    void api
      .vaultStatus()
      .then((r) => {
        setExists(r.exists);
        if (r.biometric) {
          setBiometric(r.biometric);
          setEnrollOnUnlock(!r.exists && r.biometric.available);
          if (r.exists && r.biometric.available && r.biometric.enrolled && !autoPromptedRef.current) {
            autoPromptedRef.current = true;
            void unlockWithBiometric(r.biometric.label);
          }
        }
      })
      .catch(() => setExists(false));
  }, [unlockWithBiometric]);

  const generateShareQr = useCallback(async (entry: PasswordEntry, mode: "lan" | "direct", ttl: number) => {
    setShareBurned(false);
    setShareClaimed(false);
    try {
      const res = await api.vaultShareCreate(entry, mode, ttl);
      setShareData(res);
      setShareRemaining(ttl);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    if (!sharingEntry) return;
    void generateShareQr(sharingEntry, shareMode, shareTtl);
  }, [sharingEntry, shareMode, shareTtl, generateShareQr]);

  useEffect(() => {
    if (!sharingEntry || !shareData?.shareId || shareBurned) return;
    const id = setInterval(() => {
      const left = Math.max(0, Math.floor(((shareData.expiresAt || 0) - Date.now()) / 1000));
      setShareRemaining(left);
      if (left <= 0) {
        setShareBurned(true);
        return;
      }
      if (shareData.mode === "lan" && shareData.shareId) {
        void api
          .vaultShareStatus(shareData.shareId)
          .then((st) => {
            if (st.claimed) {
              setShareClaimed(true);
              setShareBurned(true);
            } else if (st.burned || st.expired) {
              setShareBurned(true);
            }
          })
          .catch(() => {});
      }
    }, 600);
    return () => clearInterval(id);
  }, [sharingEntry, shareData, shareBurned]);

  const closeShareModal = () => {
    if (shareData?.shareId) {
      void api.vaultShareBurn(shareData.shareId).catch(() => {});
    }
    setSharingEntry(null);
    setShareData(null);
    setShareBurned(false);
    setShareClaimed(false);
  };

  const unlock = async () => {
    if (!passphrase.trim()) return;
    setError(null);
    setBioNotice(null);
    const shouldEnroll = biometric.available && !biometric.enrolled && enrollOnUnlock;
    if (shouldEnroll) setBioBusy(true);
    try {
      const res = await api.vaultUnlock(passphrase, shouldEnroll);
      if (res.ok) {
        setEntries(res.entries || []);
        setUnlocked(true);
        setExists(true);
        setJustLocked(false);
        if (res.biometric) setBiometric(res.biometric);
        if (res.biometricWarning) {
          setBioNotice(res.biometricWarning);
        } else if (shouldEnroll && res.biometric?.enrolled) {
          setBioNotice(`${res.biometric.label} enabled - next time it will prompt automatically.`);
        }
      } else {
        setError(res.error || "Wrong master password.");
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBioBusy(false);
    }
  };

  const toggleBiometric = async () => {
    setError(null);
    setBioNotice(null);
    setBioBusy(true);
    try {
      if (biometric.enrolled) {
        const res = await api.vaultBiometricDisable();
        if (res.biometric) setBiometric(res.biometric);
        setBioNotice(`${biometric.label} unlock disabled for this PC.`);
      } else {
        const res = await api.vaultBiometricEnable(passphrase || undefined);
        if (res.biometric) setBiometric(res.biometric);
        setBioNotice(`${res.biometric?.label || biometric.label} enabled - Vault will auto-ask next time.`);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBioBusy(false);
    }
  };

  const lockVault = async () => {
    await api.vaultLock().catch(() => {});
    setUnlocked(false);
    setEntries([]);
    setPassphrase("");
    setError(null);
    setBioNotice(null);
    setJustLocked(true);
  };

  const persist = async (next: PasswordEntry[]) => {
    setEntries(next);
    await api.vaultSave(next, passphrase || undefined);
    setExists(true);
  };

  const addEntry = async () => {
    if (!title.trim() || !password.trim()) return;
    const now = Date.now();
    const item: PasswordEntry = {
      id: newId("pw"),
      title: title.trim(),
      username: username.trim(),
      password: password.trim(),
      url: url.trim(),
      notes: "",
      createdAt: now,
      updatedAt: now,
    };
    await persist([item, ...entries]);
    setTitle("");
    setUsername("");
    setPassword("");
    setUrl("");
  };

  const importSharedCode = async () => {
    if (!importCode.trim()) return;
    setImportBusy(true);
    setImportErr(null);
    try {
      const res = await api.vaultShareImport(importCode.trim());
      if (res.ok && res.entry) {
        await persist([res.entry, ...entries]);
        setBioNotice(`Imported shared login “${res.entry.title}”${res.burnedOnSender ? " (burned on sender)" : ""}.`);
        setImportCode("");
        setImportOpen(false);
      } else {
        setImportErr(res.error || "Could not import shared password.");
      }
    } catch (e) {
      setImportErr(e instanceof Error ? e.message : String(e));
    } finally {
      setImportBusy(false);
    }
  };

  if (!unlocked) {
    const bioEnrolled = Boolean(exists && biometric.available && biometric.enrolled);
    return (
      <div className="max-w-md mx-auto vk-card p-6 space-y-4 mt-8 text-center">
        <div
          onClick={() => {
            if (bioEnrolled && !bioBusy) void unlockWithBiometric(biometric.label);
          }}
          title={bioEnrolled ? `Tap to scan ${biometric.label} again` : undefined}
          className={`w-14 h-14 mx-auto rounded-full bg-[#EAFF87] text-[#1B201C] flex items-center justify-center ${
            bioEnrolled ? "cursor-pointer" : ""
          } ${bioBusy ? "animate-pulse" : ""}`}
        >
          {bioEnrolled ? <Fingerprint className="w-7 h-7" /> : <Lock className="w-6 h-6" />}
        </div>
        <h2 className="text-xl font-bold">Encrypted Password Vault</h2>
        <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
          {bioBusy && bioEnrolled
            ? `Waiting for ${biometric.label}… place your finger on the sensor.`
            : exists
            ? bioEnrolled
              ? justLocked
                ? `Vault locked. Tap the fingerprint icon for ${biometric.label} or enter your master password.`
                : `${biometric.label} prompts automatically, or enter your master password below.`
              : "Enter your master password to decrypt your AES-256-GCM vault."
            : "Choose a master password to create your local AES-256-GCM encrypted vault."}
        </p>

        <input
          type="password"
          value={passphrase}
          onChange={(e) => setPassphrase(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") void unlock();
          }}
          placeholder="Master password"
          className="w-full vk-well px-4 py-2.5 text-sm border-0 outline-none"
        />

        {biometric.available && !biometric.enrolled && (
          <label
            className="flex items-center justify-center gap-2 text-xs cursor-pointer select-none py-1"
            style={{ color: "var(--vk-ink-2)" }}
          >
            <input
              type="checkbox"
              checked={enrollOnUnlock}
              onChange={(e) => setEnrollOnUnlock(e.target.checked)}
              className="rounded cursor-pointer"
            />
            <Fingerprint className="w-3.5 h-3.5" style={{ color: "var(--vk-accent)" }} />
            <span>Auto-unlock with {biometric.label} on this PC</span>
          </label>
        )}

        {error && <div className="text-xs" style={{ color: "var(--vk-danger)" }}>{error}</div>}

        <button
          onClick={() => void unlock()}
          disabled={bioBusy}
          className="vk-btn-lime w-full py-2.5 text-sm cursor-pointer inline-flex items-center justify-center gap-2 disabled:opacity-60"
        >
          <Unlock className="w-4 h-4" />{" "}
          {bioBusy ? `Verifying ${biometric.label}…` : exists ? "Unlock with Master Password" : "Create & Unlock"}
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <ShieldCheck className="w-5 h-5" style={{ color: "var(--vk-low)" }} />
          <h2 className="text-2xl font-bold tracking-tight">Password Vault</h2>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => {
              setImportOpen(true);
              setImportErr(null);
            }}
            className="vk-btn-outline px-3.5 py-1.5 text-xs cursor-pointer inline-flex items-center gap-1.5"
          >
            <QrCode className="w-3.5 h-3.5" /> Receive 1-Time Share
          </button>
          {biometric.available && (
            <button
              onClick={() => void toggleBiometric()}
              disabled={bioBusy}
              className={`${biometric.enrolled ? "vk-btn-lime" : "vk-btn-outline"} px-3.5 py-1.5 text-xs cursor-pointer inline-flex items-center gap-1.5 disabled:opacity-60`}
              title={biometric.enrolled ? `Click to turn off ${biometric.label}` : `Turn on ${biometric.label} unlock`}
            >
              <Fingerprint className="w-3.5 h-3.5" />
              {bioBusy
                ? "Verifying…"
                : biometric.enrolled
                ? `${biometric.label} Active`
                : `Enable ${biometric.label}`}
            </button>
          )}
          <button
            onClick={() => void lockVault()}
            className="vk-btn-outline px-4 py-1.5 text-xs cursor-pointer"
          >
            Lock Vault
          </button>
        </div>
      </div>

      {(bioNotice || error) && (
        <div
          className="vk-card px-4 py-2.5 text-xs flex items-center gap-2"
          style={{ color: error ? "var(--vk-danger)" : "var(--vk-ink)" }}
        >
          <Fingerprint className="w-4 h-4 shrink-0" style={{ color: error ? "var(--vk-danger)" : "var(--vk-low)" }} />
          <span>{error || bioNotice}</span>
        </div>
      )}

      <div className="vk-card p-5 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-2.5">
        <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Site / App" className="vk-well px-3 py-2 text-sm border-0 outline-none" />
        <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="Username / email" className="vk-well px-3 py-2 text-sm border-0 outline-none" />
        <div className="vk-well px-3 py-2 flex items-center gap-1">
          <input value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Password" className="bg-transparent border-0 outline-none text-sm flex-1 min-w-0" />
          <button
            onClick={async () => setPassword((await api.vaultGenerate()).password)}
            className="p-1 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer"
            title="Generate strong password"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </button>
        </div>
        <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="URL (optional)" className="vk-well px-3 py-2 text-sm border-0 outline-none" />
        <button onClick={() => void addEntry()} className="vk-btn-lime px-4 py-2 text-xs cursor-pointer inline-flex items-center justify-center gap-1">
          <KeyRound className="w-4 h-4" /> Save Login
        </button>
      </div>

      <div className="space-y-2">
        {entries.map((e) => (
          <div key={e.id} className="vk-card p-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <div className="font-semibold text-sm">{e.title}</div>
              <div className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                {e.username || "-"} {e.url ? `· ${e.url}` : ""}
              </div>
              <div className="text-xs font-mono mt-1">
                {revealed[e.id] ? e.password : "••••••••••••"}
              </div>
            </div>
            <div className="flex items-center gap-1.5">
              <button
                onClick={() => setSharingEntry(e)}
                className="vk-btn-lime px-3 py-1.5 text-xs cursor-pointer inline-flex items-center gap-1"
                title="Share with a friend via one-time QR code"
              >
                <QrCode className="w-3.5 h-3.5" /> 1-Time QR
              </button>
              <button
                onClick={() => setRevealed((r) => ({ ...r, [e.id]: !r[e.id] }))}
                className="vk-btn-outline p-2 cursor-pointer"
                title="Show / hide"
              >
                {revealed[e.id] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
              <button
                onClick={async () => {
                  await navigator.clipboard.writeText(e.password).catch(() => {});
                  setCopiedId(e.id);
                  setTimeout(() => setCopiedId(null), 1800);
                }}
                className="vk-btn-outline px-3 py-1.5 text-xs cursor-pointer inline-flex items-center gap-1"
              >
                {copiedId === e.id ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
                Copy
              </button>
              <button
                onClick={() => void persist(entries.filter((x) => x.id !== e.id))}
                className="p-2 rounded-full hover:bg-red-500/15 bg-transparent border-0 cursor-pointer"
              >
                <Trash2 className="w-4 h-4" style={{ color: "var(--vk-danger)" }} />
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Share 1-Time QR Modal */}
      {sharingEntry && (
        <div
          onClick={closeShareModal}
          className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="vk-card max-w-md w-full p-6 space-y-4"
          >
            <div className="flex items-center justify-between">
              <div>
                <h3 className="font-bold text-base">Share “{sharingEntry.title}”</h3>
                <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                  Encrypted one-time QR code for a friend's Voxban Vault
                </p>
              </div>
              <button onClick={closeShareModal} className="p-1.5 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex gap-2">
              <button
                onClick={() => setShareMode("lan")}
                className={`flex-1 py-1.5 text-xs rounded-full cursor-pointer border ${
                  shareMode === "lan" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                }`}
              >
                Same Wi-Fi (Instant Burn)
              </button>
              <button
                onClick={() => setShareMode("direct")}
                className={`flex-1 py-1.5 text-xs rounded-full cursor-pointer border ${
                  shareMode === "direct" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                }`}
              >
                Any Network (Offline)
              </button>
            </div>

            <div className="flex items-center gap-2 text-xs">
              <span style={{ color: "var(--vk-ink-2)" }}>Expires in:</span>
              {[120, 300].map((sec) => (
                <button
                  key={sec}
                  onClick={() => setShareTtl(sec)}
                  className={`px-3 py-1 rounded-full text-xs cursor-pointer border ${
                    shareTtl === sec ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                  }`}
                >
                  {sec / 60} min
                </button>
              ))}
            </div>

            {shareBurned ? (
              <div className="vk-well p-6 text-center space-y-3">
                {shareClaimed ? (
                  <CheckCircle2 className="w-10 h-10 mx-auto" style={{ color: "var(--vk-low)" }} />
                ) : (
                  <Flame className="w-10 h-10 mx-auto" style={{ color: "var(--vk-danger)" }} />
                )}
                <div className="font-bold text-sm">
                  {shareClaimed ? "Claimed & Burned!" : "One-Time QR Burned"}
                </div>
                <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                  {shareClaimed
                    ? "Your friend received the login and this QR code was automatically destroyed."
                    : "This QR code expired or was burned and can no longer be used."}
                </p>
                <button
                  onClick={() => void generateShareQr(sharingEntry, shareMode, shareTtl)}
                  className="vk-btn-lime px-4 py-2 text-xs cursor-pointer"
                >
                  Generate New 1-Time QR
                </button>
              </div>
            ) : (
              <div className="bg-white rounded-2xl p-5 flex flex-col items-center gap-3">
                {shareData?.qrSvg && (
                  <div
                    className="w-56 h-56 flex items-center justify-center [&>svg]:w-full [&>svg]:h-full"
                    dangerouslySetInnerHTML={{ __html: shareData.qrSvg }}
                  />
                )}
                <div className="px-3 py-1 rounded-full bg-[#EAFF87] text-[#1B201C] text-xs font-semibold">
                  Expires in {Math.floor(shareRemaining / 60)}:{String(shareRemaining % 60).padStart(2, "0")}
                </div>
                <p className="text-[11px] text-center text-gray-600">
                  {shareData?.mode === "lan"
                    ? "AES-256-GCM encrypted. Auto-destroys immediately after the first scan."
                    : "AES-256-GCM encrypted. Works offline across any network and can only be imported once."}
                </p>
              </div>
            )}

            {!shareBurned && (
              <div className="flex gap-2">
                <button
                  onClick={async () => {
                    if (shareData?.qrUrl) await navigator.clipboard.writeText(shareData.qrUrl).catch(() => {});
                  }}
                  className="vk-btn-outline flex-1 py-2 text-xs cursor-pointer"
                >
                  Copy Code
                </button>
                <button
                  onClick={() => {
                    if (shareData?.shareId) void api.vaultShareBurn(shareData.shareId).catch(() => {});
                    setShareBurned(true);
                  }}
                  className="flex-1 py-2 text-xs rounded-full cursor-pointer border-0 font-semibold"
                  style={{ background: "var(--vk-danger-soft)", color: "var(--vk-danger)" }}
                >
                  Burn Now
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Receive 1-Time Share Modal */}
      {importOpen && (
        <div
          onClick={() => setImportOpen(false)}
          className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
        >
          <div onClick={(e) => e.stopPropagation()} className="vk-card max-w-md w-full p-6 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="font-bold text-base">Receive Shared Password</h3>
              <button onClick={() => setImportOpen(false)} className="p-1.5 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer">
                <X className="w-4 h-4" />
              </button>
            </div>
            <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
              Paste a one-time share link (`voxban://pwshare?...`) from your friend's Voxban Android or Desktop app.
            </p>
            <textarea
              rows={3}
              value={importCode}
              onChange={(e) => setImportCode(e.target.value)}
              placeholder="voxban://pwshare?..."
              className="w-full vk-well px-3.5 py-2.5 text-xs font-mono border-0 outline-none resize-none"
            />
            {importErr && <div className="text-xs" style={{ color: "var(--vk-danger)" }}>{importErr}</div>}
            <button
              onClick={() => void importSharedCode()}
              disabled={importBusy || !importCode.trim()}
              className="vk-btn-lime w-full py-2.5 text-xs font-semibold cursor-pointer disabled:opacity-60"
            >
              {importBusy ? "Decrypting…" : "Claim & Save to My Vault"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export function FocusView({ state, onSaveState }: { state: AppState; onSaveState: (n: AppState) => void }) {
  const [minutes, setMinutes] = useState(25);
  const [remainingSec, setRemainingSec] = useState<number | null>(null);
  const [startedAt, setStartedAt] = useState(0);

  useEffect(() => {
    if (remainingSec === null) return;
    if (remainingSec <= 0) {
      onSaveState({
        ...state,
        focusSessions: [
          {
            id: newId("foc"),
            startedAt,
            plannedMin: minutes,
            actualSec: minutes * 60,
            completed: true,
          },
          ...state.focusSessions,
        ],
      });
      setRemainingSec(null);
      return;
    }
    const id = setInterval(() => setRemainingSec((s) => (s !== null ? s - 1 : null)), 1000);
    return () => clearInterval(id);
  }, [remainingSec, minutes, startedAt, state, onSaveState]);

  const progress = remainingSec !== null ? 1 - remainingSec / (minutes * 60) : 0;

  return (
    <div className="max-w-xl mx-auto space-y-6">
      <div className="vk-card p-8 text-center space-y-5">
        <div className="w-12 h-12 mx-auto rounded-full bg-[#EAFF87] text-[#1B201C] flex items-center justify-center">
          <Timer className="w-6 h-6" />
        </div>
        <h2 className="text-2xl font-bold">Focus Mode</h2>
        {remainingSec !== null ? (
          <div className="space-y-5">
            <div className="relative w-52 h-52 mx-auto flex items-center justify-center">
              <svg viewBox="0 0 120 120" className="w-full h-full -rotate-90">
                <circle cx="60" cy="60" r="52" stroke="var(--vk-well)" strokeWidth="8" fill="none" />
                <circle
                  cx="60"
                  cy="60"
                  r="52"
                  stroke="#204B37"
                  strokeWidth="8"
                  strokeLinecap="round"
                  fill="none"
                  strokeDasharray={326.7}
                  strokeDashoffset={326.7 * (1 - progress)}
                />
              </svg>
              <span className="absolute text-4xl font-bold font-mono">{formatDuration(remainingSec)}</span>
            </div>
            <button
              onClick={() => setRemainingSec(null)}
              className="vk-btn-outline px-6 py-2 text-sm cursor-pointer"
            >
              End Session Early
            </button>
          </div>
        ) : (
          <div className="space-y-5">
            <p className="text-sm" style={{ color: "var(--vk-ink-2)" }}>
              Pick a distraction-free deep work timer.
            </p>
            <div className="flex flex-wrap justify-center gap-2">
              {[15, 25, 45, 60, 90].map((m) => (
                <button
                  key={m}
                  onClick={() => setMinutes(m)}
                  className={`px-4 py-2 text-sm rounded-full cursor-pointer border ${
                    minutes === m ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                  }`}
                >
                  {m} min
                </button>
              ))}
            </div>
            <button
              onClick={() => {
                setStartedAt(Date.now());
                setRemainingSec(minutes * 60);
              }}
              className="vk-btn-lime px-8 py-3 text-sm cursor-pointer"
            >
              Start {minutes} min Focus
            </button>
          </div>
        )}
      </div>

      <div className="vk-card p-5 space-y-2">
        <div className="font-semibold text-sm">Recent focus sessions ({state.focusSessions.length})</div>
        {state.focusSessions.slice(0, 6).map((s) => (
          <div key={s.id} className="vk-well px-3.5 py-2 text-xs flex justify-between">
            <span>{new Date(s.startedAt).toLocaleString()}</span>
            <span className="font-semibold">{Math.round(s.actualSec / 60)} min</span>
          </div>
        ))}
      </div>
    </div>
  );
}
