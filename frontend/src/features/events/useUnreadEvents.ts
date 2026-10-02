import { useEffect, useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import { get } from "../../api/catalog";

const key = "eplsync.events.lastRead";
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
export function markEventsRead(cursor: number) {
  if (Number.isSafeInteger(cursor) && cursor > snapshot()) save(cursor);
}
export function useEventReadCursor() {
  return useSyncExternalStore(subscribe, snapshot, () => 0);
}
export function useUnreadEvents() {
  const read = useEventReadCursor();
  const result = useQuery({
    queryKey: ["event-unread", read],
    queryFn: ({ signal }) =>
      get<{ count: number; cursor: number }>(
        `/events/unread?afterId=${read}`,
        signal,
      ),
  });
  useEffect(() => {
    // A replaced/restored database can have a lower sequence than this browser.
    if (result.data && result.data.cursor < read) save(0);
  }, [result.data, read]);
  return result.data?.count ?? 0;
}
