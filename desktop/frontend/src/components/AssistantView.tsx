import { useState } from "react";
import { ArrowUp, Mic, MicOff, Sparkles } from "lucide-react";
import { api } from "../api";
import type { AppState, ChatMessage } from "../types";
import { newId } from "../types";
import { VoxbanAvatar } from "./VoxbanAvatar";

interface Props {
  state: AppState;
  onReplaceState: (next: AppState) => void;
}

export function AssistantView({ state, onReplaceState }: Props) {
  const [messages, setMessages] = useState<ChatMessage[]>(state.voiceChat);
  const [draft, setDraft] = useState("");
  const [thinking, setThinking] = useState(false);
  const [listening, setListening] = useState(false);

  const send = async (text = draft) => {
    const clean = text.trim();
    if (!clean || thinking) return;
    setDraft("");
    const userMsg: ChatMessage = {
      id: newId("m"),
      fromUser: true,
      text: clean,
      createdAt: Date.now(),
      changes: [],
      failures: [],
      isError: false,
      fromVoice: false,
    };
    setMessages((prev) => [...prev, userMsg]);
    setThinking(true);
    try {
      const res = await api.chat(clean);
      const botMsg: ChatMessage = {
        id: newId("m"),
        fromUser: false,
        text: res.reply,
        createdAt: Date.now(),
        changes: res.changes,
        failures: [],
        engine: res.engine,
        isError: false,
        fromVoice: false,
      };
      setMessages((prev) => [...prev, botMsg]);
      onReplaceState(res.state);
      if (state.settings.speakReplies && "speechSynthesis" in window) {
        const u = new SpeechSynthesisUtterance(res.reply);
        window.speechSynthesis.speak(u);
      }
    } catch (e) {
      setMessages((prev) => [
        ...prev,
        {
          id: newId("m"),
          fromUser: false,
          text: `Error: ${String(e)}`,
          createdAt: Date.now(),
          changes: [],
          failures: [],
          isError: true,
          fromVoice: false,
        },
      ]);
    } finally {
      setThinking(false);
    }
  };

  const toggleVoice = () => {
    const SR =
      (window as unknown as { SpeechRecognition?: new () => unknown; webkitSpeechRecognition?: new () => unknown })
        .SpeechRecognition ||
      (window as unknown as { webkitSpeechRecognition?: new () => unknown }).webkitSpeechRecognition;
    if (!SR) {
      alert("Voice recognition is not supported in this browser/WebView. You can still type below!");
      return;
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const rec = new (SR as any)();
    rec.lang = state.settings.language || "en-US";
    rec.interimResults = false;
    setListening(true);
    rec.onresult = (evt: { results: { 0: { 0: { transcript: string } } } }) => {
      const spoken = evt.results[0][0].transcript;
      setListening(false);
      void send(spoken);
    };
    rec.onerror = () => setListening(false);
    rec.onend = () => setListening(false);
    rec.start();
  };

  const suggestions = [
    "Add review Q4 budget high priority",
    "Remind me to call dentist tomorrow",
    "I spent 450 on groceries",
    "Note: Launch checklist — polish docs, verify Windows build",
  ];

  return (
    <div className="flex flex-col h-[calc(100vh-140px)] max-w-3xl mx-auto">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <VoxbanAvatar size={46} mood={thinking ? "thinking" : listening ? "listening" : "idle"} />
          <div>
            <h2 className="text-xl font-bold">Voice AI Assistant</h2>
            <p className="text-xs" style={{ color: "var(--vk-ink-2)" }}>
              Engine: {state.settings.engine} (offline fallback included) · Enter adds a new line
            </p>
          </div>
        </div>
        <button
          onClick={toggleVoice}
          className={`px-4 py-2 text-xs rounded-full inline-flex items-center gap-1.5 cursor-pointer border ${
            listening ? "bg-red-600 text-white border-transparent" : "vk-btn-lime border-transparent"
          }`}
        >
          {listening ? <MicOff className="w-4 h-4" /> : <Mic className="w-4 h-4" />}
          {listening ? "Listening…" : "Talk"}
        </button>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto space-y-3 pr-1">
        {messages.length === 0 && (
          <div className="vk-card p-6 space-y-4">
            <div className="flex items-center gap-2 font-semibold text-sm">
              <Sparkles className="w-4 h-4" /> Try asking Voxban:
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {suggestions.map((s) => (
                <button
                  key={s}
                  onClick={() => void send(s)}
                  className="vk-well p-3 text-left text-xs hover:opacity-85 cursor-pointer border-0"
                >
                  “{s}”
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={`flex ${m.fromUser ? "justify-end" : "justify-start"}`}>
            <div
              className="max-w-[82%] rounded-3xl px-4 py-3 text-sm select-text whitespace-pre-wrap"
              style={{
                background: m.fromUser ? "#EAFF87" : m.isError ? "var(--vk-danger-soft)" : "var(--vk-raised)",
                color: m.fromUser ? "#1B201C" : "var(--vk-ink)",
                border: m.fromUser ? "none" : "1px solid var(--vk-border)",
              }}
            >
              <div>{m.text}</div>
              {m.changes.length > 0 && (
                <div className="mt-2 pt-2 border-t border-black/10 space-y-0.5 text-xs opacity-80">
                  {m.changes.map((ch, idx) => (
                    <div key={idx}>✓ {ch}</div>
                  ))}
                </div>
              )}
              {m.engine && (
                <div className="mt-1 text-[10px] opacity-60">via {m.engine}</div>
              )}
            </div>
          </div>
        ))}
        {thinking && (
          <div className="vk-card inline-block px-4 py-2 text-xs" style={{ color: "var(--vk-ink-2)" }}>
            Thinking…
          </div>
        )}
      </div>

      {/* Composer: Enter ALWAYS adds a new line; click Send (or Ctrl/Cmd+Enter) to submit */}
      <div className="vk-card p-2 mt-3 flex items-end gap-2">
        <textarea
          rows={2}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
              e.preventDefault();
              void send();
            }
          }}
          placeholder="Message Voxban… (Enter for new line, Ctrl+Enter or tap arrow to send)"
          className="flex-1 bg-transparent border-0 outline-none text-sm px-3 py-2 resize-none"
        />
        <button
          onClick={() => void send()}
          disabled={thinking || !draft.trim()}
          className="w-11 h-11 rounded-full bg-[#1B201C] text-[#EAFF87] flex items-center justify-center border-0 cursor-pointer disabled:opacity-30 shrink-0"
          title="Send message"
        >
          <ArrowUp className="w-5 h-5" />
        </button>
      </div>
    </div>
  );
}
