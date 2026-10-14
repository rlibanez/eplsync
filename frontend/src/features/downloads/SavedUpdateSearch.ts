import { get } from "../../api/catalog";
import type { Page } from "./shared";
export interface SavedSearch<T> {
  rows: T[];
  searchedAt: number;
  states?: string[];
  origin?: "MANUAL" | "SCHEDULED";
  criteria?: Record<string, unknown>;
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

/** Results belong to the authenticated user and are stored only in the backend. */
export async function latestSavedSearch<T>(
  type: "downloads" | "calibre",
  signal?: AbortSignal,
): Promise<SavedSearch<T> | null> {
  const result = await get<{ search: SavedSearch<T> | null }>(
    `/settings/tasks/searches/${type}`,
    signal,
  );
  return result.search;
}
