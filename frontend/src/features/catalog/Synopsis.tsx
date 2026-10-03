import { Fragment, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

type Tag = "b" | "i" | "url" | "spoiler" | "list";
export type SynopsisNode = string | { tag: Tag | "item"; argument?: string; children: SynopsisNode[] };

// Only recognized, balanced BBCode is interpreted. Everything else remains text.
export function parseSynopsis(text: string): SynopsisNode[] {
  const root: SynopsisNode[] = [];
  const stack: { tag: Tag; argument?: string; children: SynopsisNode[]; start: number }[] = [];
  const target = () => stack.at(-1)?.children ?? root;
  const tokens = /\[(\/?)(b|i|url|spoiler|list)(?:=([^\]\r\n]*))?\]|\[\*\]/gi;
  let cursor = 0;
  for (const match of text.matchAll(tokens)) {
    target().push(text.slice(cursor, match.index));
    cursor = match.index + match[0].length;
    const tag = match[2]?.toLowerCase() as Tag;
    if (match[0] === "[*]") {
      target().push(stack.at(-1)?.tag === "list" ? { tag: "item", children: [] } : match[0]);
    } else if (match[1]) {
      if (stack.at(-1)?.tag === tag && match[3] === undefined) {
        const frame = stack.pop()!;
        target().push({ tag, argument: frame.argument, children: frame.children });
      } else target().push(match[0]);
    } else if (stack.length < 32 && (match[3] === undefined || tag === "url" || (tag === "list" && match[3] === "1"))) {
      stack.push({ tag, argument: match[3], children: [], start: match.index });
    } else target().push(match[0]);
  }
  target().push(text.slice(cursor));
  if (stack.length) root.push(text.slice(stack[0].start));
  return root;
}

export function safeSynopsisUrl(value: string): string | undefined {
  try {
    const url = new URL(value.trim());
    return ["http:", "https:"].includes(url.protocol) ? url.href : undefined;
  } catch { return undefined; }
}

export function Synopsis({ text }: { text: string }) {
  const { t } = useTranslation();
  function render(nodes: SynopsisNode[], insideLink = false): ReactNode {
    return nodes.map((node, index) => {
      if (typeof node === "string") return node;
      const children = render(node.children, insideLink || node.tag === "url");
      switch (node.tag) {
        case "b": return <strong key={index}>{children}</strong>;
        case "i": return <em key={index}>{children}</em>;
        case "spoiler": return <details key={index}><summary>{t("detail.spoiler")}</summary><div>{children}</div></details>;
        case "url": {
          const raw = node.argument ?? node.children.filter(n => typeof n === "string").join("");
          const href = safeSynopsisUrl(raw);
          return href && !insideLink ? <a key={index} href={href} target="_blank" rel="noopener noreferrer">{children}</a> : <Fragment key={index}>{children}</Fragment>;
        }
        case "list": {
          const items: SynopsisNode[][] = [];
          const prefix: SynopsisNode[] = [];
          for (const child of node.children) {
            if (typeof child !== "string" && child.tag === "item") items.push([]);
            else (items.at(-1) ?? prefix).push(child);
          }
          const List = node.argument === "1" ? "ol" : "ul";
          return <Fragment key={index}>{render(prefix, insideLink)}<List>{items.map((item, i) => <li key={i}>{render(item, insideLink)}</li>)}</List></Fragment>;
        }
        case "item": return "[*]";
      }
    });
  }
  return <div className="synopsis">{render(parseSynopsis(text))}</div>;
}
