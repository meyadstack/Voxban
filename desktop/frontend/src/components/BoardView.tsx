import { useMemo, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Bell,
  Calendar as CalendarIcon,
  Check,
  ChevronLeft,
  ChevronRight,
  Plus,
  Search,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import type { AppState, CalendarReminder, Card, Priority, ReminderRepeat } from "../types";
import { newId } from "../types";

interface Props {
  state: AppState;
  onSaveState: (next: AppState) => void;
  onRunCommand: (prompt: string) => Promise<void>;
}

export function BoardView({ state, onSaveState, onRunCommand }: Props) {
  const board =
    state.workspace.boards.find((b) => b.id === state.workspace.activeBoardId) ??
    state.workspace.boards[0];

  const [query, setQuery] = useState("");
  const [priFilter, setPriFilter] = useState<Priority | "ALL">("ALL");
  const [command, setCommand] = useState("");
  const [busy, setBusy] = useState(false);
  const [addingStageId, setAddingStageId] = useState<string | null>(null);
  const [newTaskText, setNewTaskText] = useState("");
  const [newTaskPri, setNewTaskPri] = useState<Priority>("MEDIUM");
  const [calendarOpen, setCalendarOpen] = useState(false);

  const mutateBoard = (fn: (b: typeof board) => typeof board) => {
    const nextBoards = state.workspace.boards.map((b) => (b.id === board.id ? fn(b) : b));
    onSaveState({ ...state, workspace: { ...state.workspace, boards: nextBoards } });
  };

  const addCard = (stageId: string) => {
    const text = newTaskText.trim();
    if (!text) return;
    const now = Date.now();
    const card: Card = {
      id: newId("c"),
      text,
      priority: newTaskPri,
      notes: null,
      tags: [],
      dueAt: null,
      remindBeforeMin: 60,
      createdAt: now,
      updatedAt: now,
    };
    mutateBoard((b) => ({
      ...b,
      stages: b.stages.map((s) => (s.id === stageId ? { ...s, cards: [card, ...s.cards] } : s)),
    }));
    setNewTaskText("");
    setAddingStageId(null);
  };

  const moveCard = (cardId: string, fromStageId: string, dir: -1 | 1) => {
    const idx = board.stages.findIndex((s) => s.id === fromStageId);
    const nextIdx = idx + dir;
    if (idx < 0 || nextIdx < 0 || nextIdx >= board.stages.length) return;
    const card = board.stages[idx].cards.find((c) => c.id === cardId);
    if (!card) return;
    const updated = { ...card, updatedAt: Date.now() };
    mutateBoard((b) => ({
      ...b,
      stages: b.stages.map((s, i) => {
        if (i === idx) return { ...s, cards: s.cards.filter((c) => c.id !== cardId) };
        if (i === nextIdx) return { ...s, cards: [updated, ...s.cards] };
        return s;
      }),
    }));
  };

  const deleteCard = (stageId: string, cardId: string) => {
    mutateBoard((b) => ({
      ...b,
      stages: b.stages.map((s) =>
        s.id === stageId ? { ...s, cards: s.cards.filter((c) => c.id !== cardId) } : s,
      ),
    }));
  };

  const runCmd = async () => {
    if (!command.trim() || busy) return;
    setBusy(true);
    try {
      await onRunCommand(command.trim());
      setCommand("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-5">
      {/* Header row */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">{board.title}</h2>
          <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
            Kanban board · Calendar-only reminders stay out of board columns
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="vk-well px-3 py-1.5 flex items-center gap-2">
            <Search className="w-4 h-4" style={{ color: "var(--vk-ink-3)" }} />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Filter tasks…"
              className="bg-transparent border-0 outline-none text-sm w-36"
            />
          </div>
          {(["ALL", "HIGH", "MEDIUM", "LOW"] as const).map((p) => (
            <button
              key={p}
              onClick={() => setPriFilter(p)}
              className={`px-3 py-1.5 text-xs rounded-full cursor-pointer border ${
                priFilter === p ? "vk-btn-lime border-transparent" : "vk-btn-outline"
              }`}
            >
              {p === "ALL" ? "All" : p[0] + p.slice(1).toLowerCase()}
            </button>
          ))}
          <button
            onClick={() => setCalendarOpen(true)}
            className="vk-btn-lime px-4 py-2 text-xs flex items-center gap-1.5 cursor-pointer"
          >
            <CalendarIcon className="w-4 h-4" />
            Calendar &amp; Reminders ({state.calendarReminders.filter((r) => !r.done).length})
          </button>
        </div>
      </div>

      {/* Quick AI command bar */}
      <div className="vk-card p-2.5 flex items-center gap-2">
        <Sparkles className="w-4 h-4 ml-2 shrink-0" style={{ color: "var(--vk-accent)" }} />
        <input
          value={command}
          onChange={(e) => setCommand(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              void runCmd();
            }
          }}
          placeholder="Quick command: “Add ship Windows build high priority” or “Remind me to call dentist”…"
          className="flex-1 bg-transparent border-0 outline-none text-sm py-1.5"
        />
        <button
          onClick={() => void runCmd()}
          disabled={busy || !command.trim()}
          className="vk-btn-lime px-4 py-1.5 text-xs cursor-pointer disabled:opacity-40"
        >
          {busy ? "Running…" : "Run"}
        </button>
      </div>

      {/* Stages grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4 items-start">
        {board.stages.map((stage, sIdx) => {
          const cards = stage.cards.filter((c) => {
            const matchQ = !query.trim() || c.text.toLowerCase().includes(query.toLowerCase());
            const matchP = priFilter === "ALL" || c.priority === priFilter;
            return matchQ && matchP;
          });
          return (
            <div key={stage.id} className="vk-card p-4 space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="w-2.5 h-2.5 rounded-full" style={{ background: stage.color }} />
                  <h3 className="font-semibold text-sm">{stage.title}</h3>
                  <span
                    className="text-xs px-2 py-0.5 rounded-full"
                    style={{ background: "var(--vk-well)", color: "var(--vk-ink-2)" }}
                  >
                    {cards.length}
                  </span>
                </div>
                <button
                  onClick={() => setAddingStageId(addingStageId === stage.id ? null : stage.id)}
                  className="p-1.5 rounded-full hover:bg-black/5 cursor-pointer bg-transparent border-0"
                  title="Add task"
                >
                  <Plus className="w-4 h-4" />
                </button>
              </div>

              {addingStageId === stage.id && (
                <div className="vk-well p-3 space-y-2">
                  <input
                    autoFocus
                    value={newTaskText}
                    onChange={(e) => setNewTaskText(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") addCard(stage.id);
                    }}
                    placeholder="What needs doing?"
                    className="w-full bg-transparent border-0 outline-none text-sm"
                  />
                  <div className="flex items-center justify-between gap-1">
                    <div className="flex gap-1">
                      {(["HIGH", "MEDIUM", "LOW"] as Priority[]).map((p) => (
                        <button
                          key={p}
                          onClick={() => setNewTaskPri(p)}
                          className={`px-2 py-0.5 text-[10px] rounded-full cursor-pointer border ${
                            newTaskPri === p ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                          }`}
                        >
                          {p}
                        </button>
                      ))}
                    </div>
                    <button onClick={() => addCard(stage.id)} className="vk-btn-lime px-3 py-1 text-xs cursor-pointer">
                      Add
                    </button>
                  </div>
                </div>
              )}

              <div className="space-y-2.5">
                {cards.length === 0 ? (
                  <p className="text-xs py-4 text-center" style={{ color: "var(--vk-ink-3)" }}>
                    No tasks here
                  </p>
                ) : (
                  cards.map((card) => (
                    <div
                      key={card.id}
                      className="p-3.5 rounded-2xl space-y-2"
                      style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <p className="text-sm font-medium leading-snug select-text">{card.text}</p>
                        <PriorityBadge priority={card.priority} />
                      </div>
                      {card.notes && (
                        <p className="text-xs leading-relaxed select-text" style={{ color: "var(--vk-ink-2)" }}>
                          {card.notes}
                        </p>
                      )}
                      {card.dueAt && (
                        <div className="text-[11px] flex items-center gap-1" style={{ color: "var(--vk-ink-3)" }}>
                          <CalendarIcon className="w-3 h-3" />
                          {new Date(card.dueAt).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
                        </div>
                      )}
                      <div className="flex items-center justify-between pt-1">
                        <div className="flex gap-1">
                          {sIdx > 0 && (
                            <button
                              onClick={() => moveCard(card.id, stage.id, -1)}
                              className="p-1 rounded-full hover:bg-black/10 cursor-pointer bg-transparent border-0"
                              title="Move left"
                            >
                              <ArrowLeft className="w-3.5 h-3.5" />
                            </button>
                          )}
                          {sIdx < board.stages.length - 1 && (
                            <button
                              onClick={() => moveCard(card.id, stage.id, 1)}
                              className="p-1 rounded-full hover:bg-black/10 cursor-pointer bg-transparent border-0"
                              title="Move right"
                            >
                              <ArrowRight className="w-3.5 h-3.5" />
                            </button>
                          )}
                        </div>
                        <button
                          onClick={() => deleteCard(stage.id, card.id)}
                          className="p-1 rounded-full hover:bg-red-500/15 cursor-pointer bg-transparent border-0"
                          title="Delete task"
                        >
                          <Trash2 className="w-3.5 h-3.5" style={{ color: "var(--vk-danger)" }} />
                        </button>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Calendar & Calendar-Only Reminders modal */}
      {calendarOpen && (
        <CalendarModal
          state={state}
          onSaveState={onSaveState}
          onClose={() => setCalendarOpen(false)}
        />
      )}
    </div>
  );
}

function PriorityBadge({ priority }: { priority: Priority }) {
  const styles =
    priority === "HIGH"
      ? { bg: "var(--vk-danger-soft)", fg: "var(--vk-danger)" }
      : priority === "LOW"
        ? { bg: "var(--vk-low-soft)", fg: "var(--vk-low)" }
        : { bg: "var(--vk-med-soft)", fg: "var(--vk-med)" };
  return (
    <span
      className="text-[10px] font-semibold px-2 py-0.5 rounded-full shrink-0"
      style={{ background: styles.bg, color: styles.fg }}
    >
      {priority}
    </span>
  );
}

function CalendarModal({
  state,
  onSaveState,
  onClose,
}: {
  state: AppState;
  onSaveState: (next: AppState) => void;
  onClose: () => void;
}) {
  const today = new Date();
  const [month, setMonth] = useState(new Date(today.getFullYear(), today.getMonth(), 1));
  const [selected, setSelected] = useState(
    new Date(today.getFullYear(), today.getMonth(), today.getDate()),
  );
  const [mode, setMode] = useState<"REMINDER" | "TASK">("REMINDER");
  const [title, setTitle] = useState("");
  const [notes, setNotes] = useState("");
  const [time, setTime] = useState("09:00");
  const [repeat, setRepeat] = useState<ReminderRepeat>("NONE");

  const board =
    state.workspace.boards.find((b) => b.id === state.workspace.activeBoardId) ??
    state.workspace.boards[0];

  const daysInMonth = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
  const firstDow = (new Date(month.getFullYear(), month.getMonth(), 1).getDay() + 6) % 7; // Mon=0

  const sameDay = (ts: number, d: Date) => {
    const x = new Date(ts);
    return x.getFullYear() === d.getFullYear() && x.getMonth() === d.getMonth() && x.getDate() === d.getDate();
  };

  const dayReminders = useMemo(
    () => state.calendarReminders.filter((r) => sameDay(r.at, selected)),
    [state.calendarReminders, selected],
  );
  const dayTasks = useMemo(
    () =>
      board.stages.flatMap((s) =>
        s.cards
          .filter((c) => c.dueAt && sameDay(c.dueAt, selected))
          .map((c) => ({ card: c, stage: s.title })),
      ),
    [board, selected],
  );

  const addItem = () => {
    const clean = title.trim();
    if (!clean) return;
    const [hh, mm] = time.split(":").map((n) => parseInt(n, 10) || 0);
    const at = new Date(
      selected.getFullYear(),
      selected.getMonth(),
      selected.getDate(),
      hh,
      mm,
    ).getTime();
    const now = Date.now();

    if (mode === "REMINDER") {
      // Calendar-only reminder: NEVER touches workspace.boards!
      const rem: CalendarReminder = {
        id: newId("rem"),
        title: clean,
        notes: notes.trim(),
        at,
        remindBeforeMin: 15,
        repeat,
        done: false,
        createdAt: now,
        updatedAt: now,
      };
      onSaveState({ ...state, calendarReminders: [...state.calendarReminders, rem] });
    } else {
      const card: Card = {
        id: newId("c"),
        text: clean,
        priority: "MEDIUM",
        notes: notes.trim() || null,
        tags: [],
        dueAt: at,
        remindBeforeMin: 60,
        createdAt: now,
        updatedAt: now,
      };
      const nextBoards = state.workspace.boards.map((b) =>
        b.id !== board.id
          ? b
          : {
              ...b,
              stages: b.stages.map((s, i) => (i === 0 ? { ...s, cards: [card, ...s.cards] } : s)),
            },
      );
      onSaveState({ ...state, workspace: { ...state.workspace, boards: nextBoards } });
    }
    setTitle("");
    setNotes("");
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm">
      <div className="vk-card w-full max-w-3xl max-h-[90vh] overflow-y-auto p-6 space-y-5">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-xl font-bold">Calendar &amp; Reminders</h2>
            <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
              Green dot = Calendar-only reminder (hidden from Kanban board) · Red dot = Dated board task
            </p>
          </div>
          <button onClick={onClose} className="p-2 rounded-full vk-well cursor-pointer border-0">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Month grid */}
        <div className="flex items-center justify-between">
          <button
            onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))}
            className="vk-btn-outline p-2 cursor-pointer"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <span className="font-semibold">
            {month.toLocaleDateString(undefined, { month: "long", year: "numeric" })}
          </span>
          <button
            onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))}
            className="vk-btn-outline p-2 cursor-pointer"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>

        <div className="grid grid-cols-7 gap-1.5 text-center text-xs">
          {["M", "T", "W", "T", "F", "S", "S"].map((d, i) => (
            <div key={i} className="font-semibold py-1" style={{ color: "var(--vk-ink-3)" }}>
              {d}
            </div>
          ))}
          {Array.from({ length: firstDow }).map((_, i) => (
            <div key={`pad-${i}`} />
          ))}
          {Array.from({ length: daysInMonth }).map((_, idx) => {
            const d = new Date(month.getFullYear(), month.getMonth(), idx + 1);
            const isSel = sameDay(d.getTime(), selected);
            const hasRem = state.calendarReminders.some((r) => !r.done && sameDay(r.at, d));
            const hasTask = board.stages.some((s) => s.cards.some((c) => c.dueAt && sameDay(c.dueAt, d)));
            return (
              <button
                key={idx}
                onClick={() => setSelected(d)}
                className={`py-2 rounded-xl flex flex-col items-center cursor-pointer border ${
                  isSel ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                }`}
              >
                <span>{idx + 1}</span>
                <span className="flex gap-1 mt-0.5 h-1.5">
                  {hasRem && <span className="w-1.5 h-1.5 rounded-full bg-emerald-700" />}
                  {hasTask && <span className="w-1.5 h-1.5 rounded-full bg-red-600" />}
                </span>
              </button>
            );
          })}
        </div>

        {/* Selected day items */}
        <div className="space-y-3">
          <h3 className="font-semibold text-sm">
            {selected.toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}
          </h3>

          {dayReminders.length > 0 && (
            <div className="space-y-2">
              <div className="text-xs font-semibold" style={{ color: "var(--vk-low)" }}>
                Reminders · Calendar only
              </div>
              {dayReminders.map((r) => (
                <div
                  key={r.id}
                  className="p-3 rounded-2xl flex items-center justify-between gap-2"
                  style={{ background: "rgba(234,255,135,0.45)", border: "1px solid var(--vk-border)" }}
                >
                  <button
                    onClick={() =>
                      onSaveState({
                        ...state,
                        calendarReminders: state.calendarReminders.map((x) =>
                          x.id === r.id ? { ...x, done: !x.done, updatedAt: Date.now() } : x,
                        ),
                      })
                    }
                    className="flex items-center gap-2.5 text-left bg-transparent border-0 cursor-pointer flex-1"
                  >
                    <span className="w-5 h-5 rounded-full border-2 border-[#1B201C] flex items-center justify-center shrink-0">
                      {r.done && <Check className="w-3.5 h-3.5 text-[#1B201C]" />}
                    </span>
                    <div>
                      <div className={`text-sm font-semibold text-[#1B201C] ${r.done ? "line-through opacity-60" : ""}`}>
                        {r.title}
                      </div>
                      <div className="text-xs text-[#1B201C]/70">
                        <Bell className="w-3 h-3 inline mr-1" />
                        {new Date(r.at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                        {r.repeat !== "NONE" ? ` · ${r.repeat}` : ""}
                      </div>
                    </div>
                  </button>
                  <button
                    onClick={() =>
                      onSaveState({
                        ...state,
                        calendarReminders: state.calendarReminders.filter((x) => x.id !== r.id),
                      })
                    }
                    className="p-1.5 rounded-full hover:bg-black/10 bg-transparent border-0 cursor-pointer"
                  >
                    <Trash2 className="w-4 h-4 text-[#1B201C]" />
                  </button>
                </div>
              ))}
            </div>
          )}

          {dayTasks.length > 0 && (
            <div className="space-y-2">
              <div className="text-xs font-semibold" style={{ color: "var(--vk-ink-2)" }}>
                Board tasks
              </div>
              {dayTasks.map(({ card, stage }) => (
                <div key={card.id} className="vk-well p-3 flex items-center justify-between text-sm">
                  <span>{card.text}</span>
                  <span className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
                    {stage}
                  </span>
                </div>
              ))}
            </div>
          )}

          {/* Add Reminder / Board task toggle */}
          <div className="p-4 rounded-2xl space-y-3" style={{ background: "var(--vk-surface)", border: "1px solid var(--vk-border)" }}>
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => setMode("REMINDER")}
                className={`py-2 text-xs font-semibold rounded-full cursor-pointer border ${
                  mode === "REMINDER" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                }`}
              >
                🔔 Reminder (Calendar only)
              </button>
              <button
                onClick={() => setMode("TASK")}
                className={`py-2 text-xs font-semibold rounded-full cursor-pointer border ${
                  mode === "TASK" ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                }`}
              >
                🗂 Board task
              </button>
            </div>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={mode === "REMINDER" ? "Remind me to… (won't appear on Kanban board)" : "Dated task title…"}
              className="w-full vk-well px-3.5 py-2.5 text-sm border-0 outline-none"
            />
            <div className="flex flex-wrap items-center gap-2">
              <input
                type="time"
                value={time}
                onChange={(e) => setTime(e.target.value)}
                className="vk-well px-3 py-1.5 text-xs border-0 outline-none"
              />
              {mode === "REMINDER" &&
                (["NONE", "DAILY", "WEEKLY", "MONTHLY"] as ReminderRepeat[]).map((rep) => (
                  <button
                    key={rep}
                    onClick={() => setRepeat(rep)}
                    className={`px-3 py-1 text-xs rounded-full cursor-pointer border ${
                      repeat === rep ? "vk-btn-lime border-transparent" : "vk-btn-outline"
                    }`}
                  >
                    {rep === "NONE" ? "Once" : rep[0] + rep.slice(1).toLowerCase()}
                  </button>
                ))}
              <button
                onClick={addItem}
                disabled={!title.trim()}
                className="ml-auto vk-btn-lime px-4 py-1.5 text-xs cursor-pointer disabled:opacity-40"
              >
                Add {mode === "REMINDER" ? "Reminder" : "Task"}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
