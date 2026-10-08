// Account IDs remain stable when a username changes. Never adopt legacy shared keys.
const fallback = new Map<string, string>();
const unavailable = new Set<string>();
const changed = "eplsync-events-read";
function key(accountId: string, field: string) {
  return `eplsync.events.${encodeURIComponent(accountId)}.${field}`;
}
function load(accountId: string | undefined, field: string, initial: string) {
  if (!accountId) return initial;
  const storageKey = key(accountId, field);
  if (!unavailable.has(storageKey)) {
    try {
      const value = localStorage.getItem(storageKey) ?? initial;
      fallback.set(storageKey, value);
      return value;
    } catch {
      unavailable.add(storageKey);
    }
  }
  return fallback.get(storageKey) ?? initial;
}
function save(accountId: string, field: string, value: string) {
  const storageKey = key(accountId, field);
  fallback.set(storageKey, value);
  try {
    localStorage.setItem(storageKey, value);
  } catch {
    unavailable.add(storageKey);
  }
  window.dispatchEvent(new Event(changed));
}
export function eventReadCursor(accountId: string | undefined) {
  const value = Number(load(accountId, "lastRead", "0"));
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
export function eventReadItemsSnapshot(accountId: string | undefined) {
  return load(accountId, "readItems", "[]");
}
export function readItems(raw: string): number[] {
  try {
    const values = JSON.parse(raw);
    return Array.isArray(values)
      ? values.filter((id) => Number.isSafeInteger(id) && id > 0)
      : [];
  } catch {
    return [];
  }
}
export function markOperationRead(accountId: string | undefined, id: number) {
  if (
    !accountId ||
    !Number.isSafeInteger(id) ||
    id <= eventReadCursor(accountId)
  )
    return;
  const ids = readItems(eventReadItemsSnapshot(accountId));
  if (!ids.includes(id))
    save(accountId, "readItems", JSON.stringify([...ids, id].slice(-10000)));
}
export function markEventsRead(accountId: string | undefined, cursor: number) {
  if (
    !accountId ||
    !Number.isSafeInteger(cursor) ||
    cursor <= eventReadCursor(accountId)
  )
    return;
  save(accountId, "lastRead", String(cursor));
  const ids = readItems(eventReadItemsSnapshot(accountId));
  const remaining = ids.filter((id) => id > cursor);
  if (remaining.length !== ids.length)
    save(accountId, "readItems", JSON.stringify(remaining));
}
export function resetEventReadState(accountId: string | undefined) {
  if (!accountId) return;
  save(accountId, "readItems", "[]");
  save(accountId, "lastRead", "0");
}
