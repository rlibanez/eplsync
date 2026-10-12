import { normalizeFilterValues } from "./FilterTokens";
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

it("accepts EPL ID lists without changing revision or text separators", () => {
  expect(normalizeFilterValues("eplId", "123, 456;789\n123\t00042")).toEqual(["123", "456", "789", "42"]);
  for (const invalid of ["1, abc, 2", "1;0", "1 -2", "1 2.5", "9007199254740992"])
    expect(normalizeFilterValues("eplId", invalid)).toBeNull();
  expect(normalizeFilterValues("eplId", "")).toEqual([]);
  expect(normalizeFilterValues("revision", "1,2")).toEqual(["1.2"]);
  expect(normalizeFilterValues("author", "Author One; Author Two")).toEqual(["Author One; Author Two"]);
});
