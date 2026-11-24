import type { ReactNode } from "react";

type Block =
  | { kind: "heading"; level: number; text: string }
  | { kind: "bullet"; text: string }
  | { kind: "numbered"; num: string; text: string }
  | { kind: "paragraph"; text: string }
  | { kind: "images"; urls: string[] }
  | { kind: "divider" };

const HTML_IMG = /<img[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>/gi;
const MD_IMG = /!\[[^\]]*\]\((https?:\/\/[^)\s]+)\)/g;
const TABLE_SEP = /^\|?[\s:\-]+\|[\s:\-|]*$/;
const HTML_TAG = /<\/?[a-zA-Z][^>]*>/g;

function parseChangelog(markdown: string): Block[] {
  const blocks: Block[] = [];
  let pendingImgs: string[] = [];

  const flushImgs = () => {
    if (pendingImgs.length > 0) {
      blocks.push({ kind: "images", urls: Array.from(new Set(pendingImgs)) });
      pendingImgs = [];
    }
  };

  for (const rawLine of markdown.replace(/\r\n/g, "\n").split("\n")) {
    const trimmed = rawLine.trim();
    if (!trimmed) continue;

    let foundImg = false;
    for (const m of trimmed.matchAll(HTML_IMG)) {
      if (m[1]?.startsWith("http")) {
        pendingImgs.push(m[1]);
        foundImg = true;
      }
    }
    for (const m of trimmed.matchAll(MD_IMG)) {
      if (m[1]?.startsWith("http")) {
        pendingImgs.push(m[1]);
        foundImg = true;
      }
    }

    if (TABLE_SEP.test(trimmed)) continue;

    const cleaned = trimmed
      .replace(HTML_IMG, "")
      .replace(MD_IMG, "")
      .replace(HTML_TAG, "")
      .replace(/&amp;/g, "&")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .trim();

    if (cleaned.startsWith("|") && cleaned.endsWith("|")) {
      const cells = cleaned
        .slice(1, -1)
        .split("|")
        .map((c) => c.trim())
        .filter(Boolean);
      if (foundImg || cells.length === 0) continue;
      flushImgs();
      blocks.push({ kind: "bullet", text: cells.join(" — ") });
      continue;
    }

    if (!cleaned) continue;
    flushImgs();

    if (/^(-{3,}|\*{3,}|_{3,})$/.test(cleaned)) {
      blocks.push({ kind: "divider" });
    } else if (cleaned.startsWith("### ")) {
      blocks.push({ kind: "heading", level: 3, text: cleaned.slice(4).trim() });
    } else if (cleaned.startsWith("## ")) {
      blocks.push({ kind: "heading", level: 2, text: cleaned.slice(3).trim() });
    } else if (cleaned.startsWith("# ")) {
      blocks.push({ kind: "heading", level: 1, text: cleaned.slice(2).trim() });
    } else if (/^[-*•]\s+/.test(cleaned)) {
      blocks.push({ kind: "bullet", text: cleaned.replace(/^[-*•]\s+/, "") });
    } else if (/^\d+[.)]\s+/.test(cleaned)) {
      const m = cleaned.match(/^(\d+)[.)]\s+(.+)$/);
      if (m) blocks.push({ kind: "numbered", num: m[1], text: m[2] });
    } else {
      blocks.push({ kind: "paragraph", text: cleaned });
    }
  }
  flushImgs();
  return blocks;
}

function renderInline(text: string): ReactNode[] {
  const regex = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\(https?:\/\/[^)\s]+\)|\*[^*\s][^*]*\*)/g;
  const parts: ReactNode[] = [];
  let cursor = 0;
  let key = 0;

  for (const m of text.matchAll(regex)) {
    const start = m.index ?? 0;
    if (start > cursor) parts.push(text.slice(cursor, start));
    const tok = m[0];
    if (tok.startsWith("**") && tok.endsWith("**")) {
      parts.push(
        <strong key={key++} className="font-semibold" style={{ color: "var(--vk-ink)" }}>
          {tok.slice(2, -2)}
        </strong>,
      );
    } else if (tok.startsWith("`") && tok.endsWith("`")) {
      parts.push(
        <code
          key={key++}
          className="px-1.5 py-0.5 rounded-md font-mono text-[11px]"
          style={{ background: "var(--vk-raised)", color: "var(--vk-ink)", border: "1px solid var(--vk-border)" }}
        >
          {tok.slice(1, -1)}
        </code>,
      );
    } else if (tok.startsWith("[")) {
      const linkMatch = tok.match(/^\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)$/);
      if (linkMatch) {
        parts.push(
          <a
            key={key++}
            href={linkMatch[2]}
            target="_blank"
            rel="noreferrer"
            className="underline font-medium"
            style={{ color: "var(--vk-accent)" }}
          >
            {linkMatch[1]}
          </a>,
        );
      } else {
        parts.push(tok);
      }
    } else if (tok.startsWith("*") && tok.endsWith("*")) {
      parts.push(<em key={key++}>{tok.slice(1, -1)}</em>);
    } else {
      parts.push(tok);
    }
    cursor = start + tok.length;
  }
  if (cursor < text.length) parts.push(text.slice(cursor));
  return parts;
}

/** Beautified Markdown renderer for GitHub Release changelogs (strips table/HTML clutter & formats headings, bullets, code, links & images). */
export function MarkdownChangelog({ markdown }: { markdown: string }) {
  const blocks = parseChangelog(markdown);
  if (blocks.length === 0) return null;

  return (
    <div className="space-y-2.5 text-xs leading-relaxed select-text" style={{ color: "var(--vk-ink-2)" }}>
      {blocks.map((b, i) => {
        switch (b.kind) {
          case "heading":
            return (
              <div
                key={i}
                className={`font-bold pt-1.5 ${b.level === 1 ? "text-base" : b.level === 2 ? "text-sm" : "text-xs uppercase tracking-wide"}`}
                style={{ color: "var(--vk-ink)" }}
              >
                {renderInline(b.text)}
              </div>
            );
          case "bullet":
            return (
              <div key={i} className="flex items-start gap-2">
                <span
                  className="w-1.5 h-1.5 rounded-full mt-1.5 shrink-0"
                  style={{ background: "var(--vk-accent)" }}
                />
                <span className="flex-1">{renderInline(b.text)}</span>
              </div>
            );
          case "numbered":
            return (
              <div key={i} className="flex items-start gap-2">
                <span className="font-semibold shrink-0" style={{ color: "var(--vk-accent)" }}>
                  {b.num}.
                </span>
                <span className="flex-1">{renderInline(b.text)}</span>
              </div>
            );
          case "paragraph":
            return <p key={i}>{renderInline(b.text)}</p>;
          case "images":
            return (
              <div key={i} className="flex gap-2.5 overflow-x-auto py-1">
                {b.urls.map((url) => (
                  <img
                    key={url}
                    src={url}
                    alt="Release screenshot"
                    className="h-36 rounded-xl object-cover shrink-0 border"
                    style={{ borderColor: "var(--vk-border)" }}
                  />
                ))}
              </div>
            );
          case "divider":
            return <hr key={i} className="border-0 border-t my-1" style={{ borderColor: "var(--vk-border)" }} />;
        }
      })}
    </div>
  );
}
