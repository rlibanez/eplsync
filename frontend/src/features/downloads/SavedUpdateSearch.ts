import type { Page } from "./shared";
export interface SavedSearch<T> {
  rows: T[];
  searchedAt: number;
  states?: string[];
}
function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("eplsync-update-searches", 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore("searches");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
export async function savedSearch<T>(
  key: string,
  value?: SavedSearch<T>,
): Promise<SavedSearch<T> | null> {
  const db = await database();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction("searches", value ? "readwrite" : "readonly");
      const store = tx.objectStore("searches");
      const request = value ? store.put(value, key) : store.get(key);
      tx.oncomplete = () => resolve(value ?? request.result ?? null);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}
export async function collectSearch<T>(
  fetchPage: (page: number) => Promise<Page<T>>,
): Promise<T[]> {
  const rows: T[] = [];
  for (let page = 0; ; page++) {
    const result = await fetchPage(page);
    rows.push(...result.items);
    if (rows.length > 100000)
      throw new Error(
        "La búsqueda supera el límite de 100000 resultados guardados",
      );
    if (!result.meta.hasNext || !result.items.length) return rows;
  }
}
export function searchPage<T>(
  rows: T[],
  page: number,
  size: number,
  sort: string,
): Page<T> {
  const criteria = sort.split(";").map((part) => part.split(","));
  const sorted = [...rows].sort((a, b) => {
    for (const [field, direction] of criteria) {
      const x = (a as Record<string, unknown>)[field],
        y = (b as Record<string, unknown>)[field];
      const order =
        typeof x === "number" && typeof y === "number"
          ? x - y
          : String(x ?? "").localeCompare(String(y ?? ""), "es", {
              sensitivity: "base",
              ignorePunctuation: true,
            });
      if (order) return direction === "desc" ? -order : order;
    }
    return 0;
  });
  const totalPages = Math.ceil(rows.length / size);
  return {
    items: sorted.slice(page * size, (page + 1) * size),
    meta: {
      page,
      size,
      totalItems: rows.length,
      totalPages,
      hasNext: page + 1 < totalPages,
      hasPrevious: page > 0,
    },
  };
}
