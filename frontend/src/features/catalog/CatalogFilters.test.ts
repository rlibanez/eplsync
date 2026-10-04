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

it("preserves open page bounds and serializes selection bounds as numbers", async () => {
  const { catalogParams } = await import("../../api/catalog");
  const { selectionQuery, selectionFilters } = await import("./CatalogSelection");
  for (const query of ["pagesFrom=100", "pagesTo=200", "pagesFrom=100&pagesTo=200", "pagesFrom=100&pagesTo=100"]) {
    const params = catalogParams(new URLSearchParams(query));
    const request = filterRequest(params);
    const selected = selectionFilters(selectionQuery(params));
    for (const key of ["pagesFrom", "pagesTo"]) {
      const expected = new URLSearchParams(query).get(key);
      expect(request.get(key)).toBe(expected);
      expect(selected[key]).toBe(expected === null ? undefined : Number(expected));
    }
  }
});
