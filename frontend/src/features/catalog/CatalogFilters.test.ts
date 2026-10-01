import { afterEach, expect, it, vi } from "vitest";
import { filterRequest, localDay } from "./CatalogFilters";
afterEach(() => vi.useRealTimers());
it("uses today for open starts and leaves end-only ranges open to the past", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date(2026, 9, 1, 12));
  const start = filterRequest(new URLSearchParams("publicationYearFrom=2000&publicationDateFrom=2026-01-01&addedFrom=2026-01-01"));
  expect(start.get("publicationYearTo")).toBe("2026");
  expect(start.get("publicationDateTo")).toBe("2026-10-01");
  expect(start.get("insertDateBefore")).toBe(new Date(2026,9,2).toISOString());
  const end = filterRequest(new URLSearchParams("addedTo=2026-01-01"));
  expect(end.has("insertDateFrom")).toBe(false);
  expect(end.get("insertDateBefore")).toBe(new Date(2026,0,2).toISOString());
  expect(localDay()).toBe("2026-10-01");
});
