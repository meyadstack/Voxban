import { useEffect, useRef, useState } from "react";
import { Check, Copy, Edit3, Eye, Files, ImagePlus, Pin, Plus, Search, Trash2, X } from "lucide-react";
import type { AppState, Note } from "../types";
import { NOTE_COLORS, newId, noteColorHex } from "../types";

interface Props {
  state: AppState;
  onSaveState: (next: AppState) => void;
}

async function compressImageFile(file: File): Promise<string | null> {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onerror = () => resolve(null);
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => resolve(null);
      img.onload = () => {
        const maxDim = 1024;
        let w = img.width;
        let h = img.height;
        if (Math.max(w, h) > maxDim) {
          const scale = maxDim / Math.max(w, h);
          w = Math.max(1, Math.round(w * scale));
          h = Math.max(1, Math.round(h * scale));
        }
        const canvas = document.createElement("canvas");
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext("2d");
        if (!ctx) {
          resolve(null);
          return;
        }
        ctx.drawImage(img, 0, 0, w, h);
        resolve(canvas.toDataURL("image/jpeg", 0.78));
      };
      img.src = String(reader.result);
    };
    reader.readAsDataURL(file);
  });
}

/**
 * AI Note Taker aligned with Voxban Android:
 * - Direct text selection & 1-tap Copy directly in read mode
 * - Image attachments (upload or paste) with card thumbnails & full-screen lightbox
 * - Right-click / long-press context menu (Pin, Edit, Add Image, Copy, Duplicate, Colors, Delete)
 */
export function NotesView({ state, onSaveState }: Props) {
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(state.notes[0]?.id ?? null);
  const [editing, setEditing] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [contextMenu, setContextMenu] = useState<{ note: Note; x: number; y: number } | null>(null);
  const longPressTimer = useRef<number | null>(null);
  const imgInputRef = useRef<HTMLInputElement | null>(null);
  const imgTargetIdRef = useRef<string | null>(null);

  useEffect(() => {
    const close = () => setContextMenu(null);
    window.addEventListener("click", close);
    return () => window.removeEventListener("click", close);
  }, []);

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
      images: [],
      createdAt: now,
      updatedAt: now,
    };
    onSaveState({ ...state, notes: [n, ...state.notes] });
    setSelectedId(n.id);
    setEditing(true);
  };

  const duplicateNote = (src: Note) => {
    const now = Date.now();
    const copy: Note = {
      ...src,
      id: newId("n"),
      title: `${src.title} (copy)`,
      pinned: false,
      images: [...(src.images || [])],
      createdAt: now,
      updatedAt: now,
    };
    onSaveState({ ...state, notes: [copy, ...state.notes] });
    setSelectedId(copy.id);
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

  const attachFilesToNote = async (noteId: string, files: FileList | File[]) => {
    const target = state.notes.find((n) => n.id === noteId);
    if (!target) return;
    const encoded: string[] = [];
    for (const f of Array.from(files)) {
      if (!f.type.startsWith("image/")) continue;
      const dataUrl = await compressImageFile(f);
      if (dataUrl) encoded.push(dataUrl);
    }
    if (encoded.length > 0) {
      const nextImgs = [...(target.images || []), ...encoded].slice(0, 20);
      updateNote(noteId, { images: nextImgs });
    }
  };

  const triggerPickImage = (noteId: string) => {
    imgTargetIdRef.current = noteId;
    imgInputRef.current?.click();
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
      <input
        ref={imgInputRef}
        type="file"
        accept="image/*"
        multiple
        className="hidden"
        onChange={(e) => {
          const id = imgTargetIdRef.current;
          if (id && e.target.files?.length) {
            void attachFilesToNote(id, e.target.files);
          }
          e.target.value = "";
        }}
      />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Notes</h2>
          <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
            Attach or paste images · Right-click or long-press any note for Pin, Edit, Duplicate &amp; Delete
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
            const imgs = n.images || [];
            return (
              <div
                key={n.id}
                onClick={() => {
                  setSelectedId(n.id);
                  setEditing(false);
                }}
                onContextMenu={(e) => {
                  e.preventDefault();
                  setContextMenu({ note: n, x: Math.min(e.clientX, window.innerWidth - 220), y: Math.min(e.clientY, window.innerHeight - 320) });
                }}
                onPointerDown={(e) => {
                  if (e.button !== 0) return;
                  const cx = e.clientX;
                  const cy = e.clientY;
                  longPressTimer.current = window.setTimeout(() => {
                    setContextMenu({ note: n, x: Math.min(cx, window.innerWidth - 220), y: Math.min(cy, window.innerHeight - 320) });
                  }, 520);
                }}
                onPointerUp={() => {
                  if (longPressTimer.current) {
                    window.clearTimeout(longPressTimer.current);
                    longPressTimer.current = null;
                  }
                }}
                onPointerLeave={() => {
                  if (longPressTimer.current) {
                    window.clearTimeout(longPressTimer.current);
                    longPressTimer.current = null;
                  }
                }}
                className="p-4 rounded-3xl cursor-pointer transition-all"
                style={{
                  background: noteColorHex(n.color),
                  color: "#1B201C",
                  outline: isSel ? "2.5px solid #1B201C" : "1px solid rgba(27,32,28,0.1)",
                }}
              >
                {imgs.length > 0 && (
                  <div className="relative mb-2.5 h-28 rounded-2xl overflow-hidden bg-black/10">
                    <img src={imgs[0]} alt="" className="w-full h-full object-cover" />
                    {imgs.length > 1 && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 rounded-full text-[10px] font-bold bg-black/70 text-white">
                        +{imgs.length - 1}
                      </span>
                    )}
                  </div>
                )}
                <div className="flex items-start justify-between gap-2">
                  <h3 className="font-semibold text-sm line-clamp-1">{n.title}</h3>
                  {n.pinned && <Pin className="w-3.5 h-3.5 opacity-60 shrink-0" />}
                </div>
                <p className="text-xs mt-1.5 opacity-75 line-clamp-3 whitespace-pre-line">{n.body || (imgs.length ? `${imgs.length} image${imgs.length === 1 ? "" : "s"}` : "Empty note…")}</p>
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
              onPaste={(e) => {
                const items = Array.from(e.clipboardData?.items || []);
                const imageFiles = items
                  .filter((it) => it.type.startsWith("image/"))
                  .map((it) => it.getAsFile())
                  .filter((f): f is File => Boolean(f));
                if (imageFiles.length > 0) {
                  e.preventDefault();
                  void attachFilesToNote(active.id, imageFiles);
                }
              }}
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
                    onClick={() => triggerPickImage(active.id)}
                    className="px-3 py-1.5 rounded-full text-xs font-semibold bg-white/70 hover:bg-white text-[#1B201C] border-0 cursor-pointer inline-flex items-center gap-1.5"
                    title="Add image (or paste with Ctrl/Cmd+V)"
                  >
                    <ImagePlus className="w-3.5 h-3.5" /> Image
                  </button>
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

              {/* Attached images gallery */}
              {(active.images?.length ?? 0) > 0 && (
                <div className="flex gap-2.5 overflow-x-auto pb-1">
                  {(active.images || []).map((src, idx) => (
                    <div
                      key={idx}
                      className="relative shrink-0 w-36 h-28 rounded-2xl overflow-hidden border border-black/15 cursor-pointer group"
                      onClick={() => setLightboxSrc(src)}
                    >
                      <img src={src} alt={`Note image ${idx + 1}`} className="w-full h-full object-cover" />
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          updateNote(active.id, {
                            images: (active.images || []).filter((_, i) => i !== idx),
                          });
                        }}
                        title="Remove image"
                        className="absolute top-1.5 right-1.5 w-6 h-6 rounded-full bg-black/70 text-white border-0 cursor-pointer flex items-center justify-center opacity-85 hover:opacity-100"
                      >
                        <X className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  ))}
                </div>
              )}

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
                    placeholder="Write freely - # heading, - bullet, - [ ] to-do. Paste images directly or click Image above."
                    className="w-full flex-1 min-h-[300px] bg-transparent border-0 outline-none text-sm leading-relaxed resize-y text-[#1B201C]"
                  />
                </div>
              ) : (
                <div className="flex-1 space-y-3 select-text">
                  <h2 className="text-2xl font-bold select-text">{active.title}</h2>
                  <p className="text-xs opacity-60 select-none">
                    Select any text below to copy directly, paste an image, or click Image / Copy above.
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

      {/* Right-click / long-press floating menu */}
      {contextMenu && (
        <div
          onClick={(e) => e.stopPropagation()}
          className="fixed z-50 w-52 rounded-2xl p-2 shadow-2xl space-y-1"
          style={{
            left: contextMenu.x,
            top: contextMenu.y,
            background: "var(--vk-raised)",
            border: "1px solid var(--vk-border)",
          }}
        >
          <div className="flex items-center gap-1 px-2 py-1.5">
            {NOTE_COLORS.map((col) => (
              <button
                key={col.name}
                title={col.name}
                onClick={() => {
                  updateNote(contextMenu.note.id, { color: col.num });
                  setContextMenu(null);
                }}
                className="w-4 h-4 rounded-full cursor-pointer border border-black/20"
                style={{ background: col.hex }}
              />
            ))}
          </div>
          <button
            onClick={() => {
              updateNote(contextMenu.note.id, { pinned: !contextMenu.note.pinned });
              setContextMenu(null);
            }}
            className="w-full px-3 py-1.5 rounded-xl text-xs text-left flex items-center gap-2 hover:bg-black/10 bg-transparent border-0 cursor-pointer"
          >
            <Pin className="w-3.5 h-3.5" /> {contextMenu.note.pinned ? "Unpin note" : "Pin to top"}
          </button>
          <button
            onClick={() => {
              setSelectedId(contextMenu.note.id);
              setEditing(true);
              setContextMenu(null);
            }}
            className="w-full px-3 py-1.5 rounded-xl text-xs text-left flex items-center gap-2 hover:bg-black/10 bg-transparent border-0 cursor-pointer"
          >
            <Edit3 className="w-3.5 h-3.5" /> Edit note
          </button>
          <button
            onClick={() => {
              triggerPickImage(contextMenu.note.id);
              setContextMenu(null);
            }}
            className="w-full px-3 py-1.5 rounded-xl text-xs text-left flex items-center gap-2 hover:bg-black/10 bg-transparent border-0 cursor-pointer"
          >
            <ImagePlus className="w-3.5 h-3.5" /> Add image
          </button>
          <button
            onClick={() => {
              void copyNote(contextMenu.note);
              setContextMenu(null);
            }}
            className="w-full px-3 py-1.5 rounded-xl text-xs text-left flex items-center gap-2 hover:bg-black/10 bg-transparent border-0 cursor-pointer"
          >
            <Copy className="w-3.5 h-3.5" /> Copy text
          </button>
          <button
            onClick={() => {
              duplicateNote(contextMenu.note);
              setContextMenu(null);
            }}
            className="w-full px-3 py-1.5 rounded-xl text-xs text-left flex items-center gap-2 hover:bg-black/10 bg-transparent border-0 cursor-pointer"
          >
            <Files className="w-3.5 h-3.5" /> Duplicate
          </button>
          <button
            onClick={() => {
              deleteNote(contextMenu.note.id);
              setContextMenu(null);
            }}
            className="w-full px-3 py-1.5 rounded-xl text-xs text-left flex items-center gap-2 hover:bg-red-500/15 bg-transparent border-0 cursor-pointer"
            style={{ color: "var(--vk-danger)" }}
          >
            <Trash2 className="w-3.5 h-3.5" /> Delete
          </button>
        </div>
      )}

      {/* Full-screen image lightbox */}
      {lightboxSrc && (
        <div
          onClick={() => setLightboxSrc(null)}
          className="fixed inset-0 z-50 bg-black/90 flex items-center justify-center p-6"
        >
          <button
            onClick={() => setLightboxSrc(null)}
            className="absolute top-5 right-5 w-10 h-10 rounded-full bg-white/15 text-white border-0 cursor-pointer flex items-center justify-center"
          >
            <X className="w-5 h-5" />
          </button>
          <img
            src={lightboxSrc}
            alt="Full preview"
            onClick={(e) => e.stopPropagation()}
            className="max-w-full max-h-full rounded-2xl object-contain"
          />
        </div>
      )}
    </div>
  );
}
