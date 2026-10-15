import { get } from "../../api/catalog";
import type { Page } from "./shared";
export interface SavedSearch {
  searchedAt: number;
  states?: string[];
  origin?: "MANUAL" | "SCHEDULED";
  criteria?: Record<string, unknown>;
}
export function savedSearchUrl(
  type: "downloads" | "calibre",
  generation: number,
  sort: string,
  filters: Record<string, string> = {},
): string {
  const params = new URLSearchParams({ generation: String(generation) });
  for (const criterion of sort.split(";")) params.append("sort", criterion);
  for (const [key, value] of Object.entries(filters))
    if (value) params.set(key, value);
  return `/settings/tasks/searches/${type}/rows?${params}`;
}
export function savedSearchPage<T>(
  url: string,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<Page<T>> {
  return get<Page<T>>(`${url}&page=${page}&size=${size}`, signal);
}
/** Poll only metadata. Row requests are bounded and pinned to the search generation. */
export async function latestSavedSearch(
  type: "downloads" | "calibre",
  signal?: AbortSignal,
): Promise<SavedSearch | null> {
  const result = await get<{ search: SavedSearch | null }>(
    `/settings/tasks/searches/${type}`,
    signal,
  );
  return result.search;
}
