import { useEffect, useState } from "react";
import {
  Check,
  Copy,
  Eye,
  EyeOff,
  KeyRound,
  Lock,
  Plus,
  RefreshCw,
  ShieldCheck,
  Timer,
  Trash2,
  Unlock,
} from "lucide-react";
import { api } from "../api";
import type { AppState, PasswordEntry, Transaction, TxType } from "../types";
import { formatDuration, newId } from "../types";

const EXPENSE_CATS = ["Food", "Groceries", "Transport", "Shopping", "Bills", "Rent", "Health", "Education", "Other"];
const INCOME_CATS = ["Salary", "Business", "Freelance", "Investment", "Gift", "Other"];

export function MoneyView({ state, onSaveState }: { state: AppState; onSaveState: (n: AppState) => void }) {
  const sym = state.settings.currency || "$";
  const [type, setType] = useState<TxType>("EXPENSE");
  const [amount, setAmount] = useState("");
  const [category, setCategory] = useState("Food");
  const [note, setNote] = useState("");

  const income = state.transactions.filter((t) => t.type === "INCOME").reduce((s, t) => s + t.amount, 0);
  const expense = state.transactions.filter((t) => t.type === "EXPENSE").reduce((s, t) => s + t.amount, 0);

  const addTx = () => {
    const val = parseFloat(amount);
    if (!val || val <= 0) return;
    const now = Date.now();
    const tx: Transaction = {
      id: newId("tx"),
      amount: val,
      type,
      category,
      note: note.trim(),
      at: now,
      createdAt: now,
    };
    onSaveState({ ...state, transactions: [tx, ...state.transactions] });
    setAmount("");
    setNote("");
  };

  return (
    <div className="space-y-5">
      <h2 className="text-2xl font-bold tracking-tight">Money Manager</h2>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div className="vk-card p-5">
          <div className="text-xs" style={{ color: "var(--vk-ink-2)" }}>Balance</div>
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

      {/* Add transaction */}
      <div className="vk-card p-5 space-y-3">
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
        {state.transactions.length === 0 ? (
          <p className="text-xs py-6 text-center" style={{ color: "var(--vk-ink-3)" }}>
            No transactions yet.
          </p>
        ) : (
          state.transactions.map((t) => (
            <div
              key={t.id}
              className="p-3 rounded-2xl flex items-center justify-between gap-2"
              style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}
            >
              <div>
                <div className="text-sm font-medium">{t.category}</div>
                <div className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                  {t.note || new Date(t.at).toLocaleDateString()}
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
  const [passphrase, setPassphrase] = useState("");
  const [unlocked, setUnlocked] = useState(false);
  const [entries, setEntries] = useState<PasswordEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const [title, setTitle] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [url, setUrl] = useState("");

  useEffect(() => {
    void api.vaultStatus().then((r) => setExists(r.exists)).catch(() => setExists(false));
  }, []);

  const unlock = async () => {
    if (!passphrase.trim()) return;
    setError(null);
    try {
      const res = await api.vaultUnlock(passphrase);
      if (res.ok) {
        setEntries(res.entries || []);
        setUnlocked(true);
      } else {
        setError(res.error || "Wrong master password.");
      }
    } catch (e) {
      setError(String(e));
    }
  };

  const persist = async (next: PasswordEntry[]) => {
    setEntries(next);
    await api.vaultSave(passphrase, next);
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

  if (!unlocked) {
    return (
      <div className="max-w-md mx-auto vk-card p-6 space-y-4 mt-8 text-center">
        <div className="w-14 h-14 mx-auto rounded-full bg-[#EAFF87] text-[#1B201C] flex items-center justify-center">
          <Lock className="w-6 h-6" />
        </div>
        <h2 className="text-xl font-bold">Encrypted Password Vault</h2>
        <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
          {exists
            ? "Enter your master password to decrypt your AES-256-GCM vault."
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
        {error && <div className="text-xs" style={{ color: "var(--vk-danger)" }}>{error}</div>}
        <button onClick={() => void unlock()} className="vk-btn-lime w-full py-2.5 text-sm cursor-pointer inline-flex items-center justify-center gap-2">
          <Unlock className="w-4 h-4" /> {exists ? "Unlock Vault" : "Create & Unlock"}
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <ShieldCheck className="w-5 h-5" style={{ color: "var(--vk-low)" }} />
          <h2 className="text-2xl font-bold tracking-tight">Password Vault</h2>
        </div>
        <button
          onClick={() => {
            setUnlocked(false);
            setEntries([]);
            setPassphrase("");
          }}
          className="vk-btn-outline px-4 py-1.5 text-xs cursor-pointer"
        >
          Lock Vault
        </button>
      </div>

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
                {e.username || "—"} {e.url ? `· ${e.url}` : ""}
              </div>
              <div className="text-xs font-mono mt-1">
                {revealed[e.id] ? e.password : "••••••••••••"}
              </div>
            </div>
            <div className="flex items-center gap-1.5">
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
