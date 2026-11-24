import { useState } from "react";
import { Check, Copy, Edit3, Eye, Pin, Plus, Search, Trash2 } from "lucide-react";
import type { AppState, Note } from "../types";
import { NOTE_COLORS, newId, noteColorHex } from "../types";

interface Props {
  state: AppState;
  onSaveState: (next: AppState) => void;
}

/**
 * AI Note Taker aligned with Voxban Android:
 * - Direct text selection & 1-tap Copy directly in read mode (no need to click Edit first!)
 * - Pressing Enter in note body ALWAYS goes to the next line cleanly!
 */
export function NotesView({ state, onSaveState }: Props) {
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(state.notes[0]?.id ?? null);
  const [editing, setEditing] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const notes = state.notes
    .filter(
      (n) =>
        !query.trim() ||
        n.title.toLowerCase().includes(query.toLowerCase()) ||
        n.body.toLowerCase().includes(query.toLowerCase()),
    )
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt);

  const active = state.notes.find((n) => n.id === selectedId) ?? notes[0] ?? null;

  const updateNote = (id: string, patch: Partial<Note>) => {
    onSaveState({
      ...state,
      notes: state.notes.map((n) => (n.id === id ? { ...n, ...patch, updatedAt: Date.now() } : n)),
    });
  };

  const createNote = () => {
    const color = NOTE_COLORS[state.notes.length % NOTE_COLORS.length].num;
    const now = Date.now();
    const n: Note = {
      id: newId("n"),
      title: "Untitled note",
      body: "",
      color,
      pinned: false,
      source: "",
      createdAt: now,
      updatedAt: now,
    };
    onSaveState({ ...state, notes: [n, ...state.notes] });
    setSelectedId(n.id);
    setEditing(true);
  };

  const deleteNote = (id: string) => {
    const next = state.notes.filter((n) => n.id !== id);
    onSaveState({ ...state, notes: next });
    if (selectedId === id) setSelectedId(next[0]?.id ?? null);
  };

  const copyNote = async (n: Note) => {
    const full = [n.title.trim(), n.body.trim()].filter(Boolean).join("\n\n");
    await navigator.clipboard.writeText(full).catch(() => {});
    setCopiedId(n.id);
    setTimeout(() => setCopiedId(null), 1800);
  };

  const toggleChecklistLine = (note: Note, lineIdx: number) => {
    const lines = note.body.split("\n");
    const line = lines[lineIdx] ?? "";
    lines[lineIdx] = line.trimStart().startsWith("- [ ]")
      ? line.replace("- [ ]", "- [x]")
      : line.replace(/- \[[xX]\]/, "- [ ]");
    updateNote(note.id, { body: lines.join("\n") });
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Notes</h2>
          <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
            Select &amp; copy any text directly without clicking Edit · Enter adds a new line
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="vk-well px-3 py-1.5 flex items-center gap-2">
            <Search className="w-4 h-4" style={{ color: "var(--vk-ink-3)" }} />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search notes…"
              className="bg-transparent border-0 outline-none text-sm w-40"
            />
          </div>
          <button onClick={createNote} className="vk-btn-lime px-4 py-2 text-xs flex items-center gap-1.5 cursor-pointer">
            <Plus className="w-4 h-4" /> New Note
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 items-start">
        {/* Left: pastel note cards */}
        <div className="lg:col-span-5 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-1 gap-3">
          {notes.map((n) => {
            const isSel = active?.id === n.id;
            return (
              <div
                key={n.id}
                onClick={() => {
                  setSelectedId(n.id);
                  setEditing(false);
                }}
                className="p-4 rounded-3xl cursor-pointer transition-all"
                style={{
                  background: noteColorHex(n.color),
                  color: "#1B201C",
                  outline: isSel ? "2.5px solid #1B201C" : "1px solid rgba(27,32,28,0.1)",
                }}
              >
                <div className="flex items-start justify-between gap-2">
                  <h3 className="font-semibold text-sm line-clamp-1">{n.title}</h3>
                  {n.pinned && <Pin className="w-3.5 h-3.5 opacity-60 shrink-0" />}
                </div>
                <p className="text-xs mt-1.5 opacity-75 line-clamp-3 whitespace-pre-line">{n.body || "Empty note…"}</p>
              </div>
            );
          })}
          {notes.length === 0 && (
            <div className="vk-card p-6 text-center text-sm" style={{ color: "var(--vk-ink-2)" }}>
              No notes yet. Click <b>New Note</b> to create one.
            </div>
          )}
        </div>

        {/* Right: reader/editor */}
        <div className="lg:col-span-7">
          {active ? (
            <div
              className="rounded-3xl p-6 space-y-4 min-h-[480px] flex flex-col"
              style={{
                background: noteColorHex(active.color),
                color: "#1B201C",
                border: "1px solid rgba(27,32,28,0.12)",
              }}
            >
              {/* Toolbar */}
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-1.5">
                  {NOTE_COLORS.map((col) => (
                    <button
                      key={col.name}
                      onClick={() => updateNote(active.id, { color: col.num })}
                      title={col.name}
                      className="w-6 h-6 rounded-full cursor-pointer"
                      style={{
                        background: col.hex,
                        border: active.color === col.num ? "2.5px solid #1B201C" : "1px solid rgba(27,32,28,0.22)",
                      }}
                    />
                  ))}
                </div>
                <div className="flex items-center gap-1.5">
                  <button
                    onClick={() => void copyNote(active)}
                    className="px-3 py-1.5 rounded-full text-xs font-semibold bg-white/70 hover:bg-white text-[#1B201C] border-0 cursor-pointer inline-flex items-center gap-1.5"
                    title="Copy entire note"
                  >
                    {copiedId === active.id ? <Check className="w-3.5 h-3.5 text-emerald-700" /> : <Copy className="w-3.5 h-3.5" />}
                    {copiedId === active.id ? "Copied" : "Copy"}
                  </button>
                  <button
                    onClick={() => updateNote(active.id, { pinned: !active.pinned })}
                    className="p-2 rounded-full bg-white/70 hover:bg-white text-[#1B201C] border-0 cursor-pointer"
                    title={active.pinned ? "Unpin" : "Pin"}
                  >
                    <Pin className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => setEditing((v) => !v)}
                    className="px-3 py-1.5 rounded-full text-xs font-semibold bg-white/70 hover:bg-white text-[#1B201C] border-0 cursor-pointer inline-flex items-center gap-1.5"
                  >
                    {editing ? <Eye className="w-3.5 h-3.5" /> : <Edit3 className="w-3.5 h-3.5" />}
                    {editing ? "Preview" : "Edit"}
                  </button>
                  <button
                    onClick={() => deleteNote(active.id)}
                    className="p-2 rounded-full bg-white/70 hover:bg-red-100 text-red-700 border-0 cursor-pointer"
                    title="Delete note"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              </div>

              {editing ? (
                <div className="flex-1 flex flex-col gap-3">
                  <input
                    value={active.title}
                    onChange={(e) => updateNote(active.id, { title: e.target.value })}
                    placeholder="Title"
                    className="w-full text-2xl font-bold bg-transparent border-0 outline-none text-[#1B201C]"
                  />
                  <textarea
                    value={active.body}
                    onChange={(e) => updateNote(active.id, { body: e.target.value })}
                    placeholder="Write freely - # heading, - bullet, - [ ] to-do. Press Enter for a new line."
                    className="w-full flex-1 min-h-[340px] bg-transparent border-0 outline-none text-sm leading-relaxed resize-y text-[#1B201C]"
                  />
                </div>
              ) : (
                <div className="flex-1 space-y-3 select-text">
                  <h2 className="text-2xl font-bold select-text">{active.title}</h2>
                  <p className="text-xs opacity-60 select-none">
                    Select any text below to copy directly, or click Copy above.
                  </p>
                  <div className="space-y-1.5 text-sm leading-relaxed select-text">
                    {active.body.split("\n").map((line, i) => {
                      const t = line.trimStart();
                      if (!t) return <div key={i} className="h-2" />;
                      if (t.startsWith("# "))
                        return (
                          <h3 key={i} className="text-lg font-bold pt-2 select-text">
                            {t.slice(2)}
                          </h3>
                        );
                      if (t.startsWith("## "))
                        return (
                          <h4 key={i} className="text-base font-bold pt-1 select-text">
                            {t.slice(3)}
                          </h4>
                        );
                      if (/^[-*]\s\[( |x|X)\]\s?/.test(t)) {
                        const done = t.toLowerCase().includes("[x]");
                        const label = t.replace(/^[-*]\s\[( |x|X)\]\s?/, "");
                        return (
                          <div key={i} className="flex items-start gap-2">
                            <input
                              type="checkbox"
                              checked={done}
                              onChange={() => toggleChecklistLine(active, i)}
                              className="mt-1 cursor-pointer accent-[#1B201C]"
                            />
                            <span className={`select-text ${done ? "line-through opacity-60" : ""}`}>
                              {label}
                            </span>
                          </div>
                        );
                      }
                      if (/^[-*•]\s/.test(t)) {
                        return (
                          <div key={i} className="flex gap-2 select-text">
                            <span className="font-bold">•</span>
                            <span>{t.slice(2)}</span>
                          </div>
                        );
                      }
                      return (
                        <p key={i} className="select-text">
                          {line}
                        </p>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="vk-card p-10 text-center text-sm" style={{ color: "var(--vk-ink-2)" }}>
              Select a note or create a new one.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
