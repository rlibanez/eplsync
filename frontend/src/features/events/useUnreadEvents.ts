import { useEffect, useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import { post } from "../downloads/shared";

const key = "eplsync.events.lastRead";
const individualKey = "eplsync.events.readItems";
let individualFallback = "[]";
const changed = "eplsync-events-read";
let fallback = 0;
let storageUnavailable = false;
function snapshot() {
  if (storageUnavailable) return fallback;
  try {
    const value = Number(localStorage.getItem(key) ?? 0);
    return Number.isSafeInteger(value) && value >= 0 ? value : 0;
  } catch {
    storageUnavailable = true;
    return fallback;
  }
}
function subscribe(listener: () => void) {
  window.addEventListener(changed, listener);
  window.addEventListener("storage", listener);
  return () => {
    window.removeEventListener(changed, listener);
    window.removeEventListener("storage", listener);
  };
}
function save(cursor: number) {
  fallback = cursor;
  try {
    localStorage.setItem(key, String(cursor));
  } catch {
    storageUnavailable = true;
    /* Optional browser storage. */
  }
  window.dispatchEvent(new Event(changed));
}
function individualSnapshot() {
  try { return localStorage.getItem(individualKey) ?? "[]"; }
  catch { return individualFallback; }
}
function readItems(raw = individualSnapshot()): number[] {
  try {
    const values = JSON.parse(raw);
    return Array.isArray(values) ? values.filter(id => Number.isSafeInteger(id) && id > 0) : [];
  } catch { return []; }
}
function saveItems(ids: number[]) {
  individualFallback = JSON.stringify(ids);
  try { localStorage.setItem(individualKey, individualFallback); } catch { /* Optional storage. */ }
  window.dispatchEvent(new Event(changed));
}
export function markOperationRead(id: number) {
  if (!Number.isSafeInteger(id) || id <= snapshot()) return;
  const ids = readItems();
  if (!ids.includes(id)) saveItems([...ids, id].slice(-10000));
}
export function markEventsRead(cursor: number) {
  if (Number.isSafeInteger(cursor) && cursor > snapshot()) {
    save(cursor);
    const ids = readItems();
    const remaining = ids.filter(id => id > cursor);
    if (remaining.length !== ids.length) saveItems(remaining);
  }
}
export function useEventReadCursor() {
  return useSyncExternalStore(subscribe, snapshot, () => 0);
}
export function useUnreadEvents() {
  const read = useEventReadCursor();
  const individual = useSyncExternalStore(subscribe, individualSnapshot, () => "[]");
  const result = useQuery({
    queryKey: ["event-unread", read, individual],
    queryFn: () => post<{ count: number; cursor: number }>("/events/unread", {
      afterId: read,
      readIds: readItems(individual),
    }),
  });
  useEffect(() => {
    // A replaced/restored database can have a lower sequence than this browser.
    if (!result.isFetching && result.data && result.data.cursor < Math.max(read, ...readItems(individual))) {
      saveItems([]);
      save(0);
    }
  }, [result.data, result.isFetching, read, individual]);
  return result.data?.count ?? 0;
}
