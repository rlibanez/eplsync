import { normalizeFilterValues } from "./FilterTokens";
import { afterEach, expect, it, vi } from "vitest";
import { filterRequest, localDay } from "./CatalogFilters";
afterEach(() => vi.useRealTimers());
it("uses today for open starts and leaves end-only ranges open to the past", () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date(2026, 9, 1, 12));
  const start = filterRequest(
    new URLSearchParams(
      "publicationYearFrom=2000&publicationDateFrom=2026-01-01&addedFrom=2026-01-01",
    ),
  );
  expect(start.get("publicationYearTo")).toBe("2026");
  expect(start.get("publicationDateTo")).toBe("2026-10-01");
  expect(start.get("insertDateBefore")).toBe(
    new Date(2026, 9, 2).toISOString(),
  );
  const end = filterRequest(new URLSearchParams("addedTo=2026-01-01"));
  expect(end.has("insertDateFrom")).toBe(false);
  expect(end.get("insertDateBefore")).toBe(new Date(2026, 0, 2).toISOString());
  expect(localDay()).toBe("2026-10-01");
});

it("preserves open page bounds and serializes selection bounds as numbers", async () => {
  const { catalogParams } = await import("../../api/catalog");
  const { selectionQuery, selectionFilters } =
    await import("./CatalogSelection");
  for (const query of [
    "pagesFrom=100",
    "pagesTo=200",
    "pagesFrom=100&pagesTo=200",
    "pagesFrom=100&pagesTo=100",
  ]) {
    const params = catalogParams(new URLSearchParams(query));
    const request = filterRequest(params);
    const selected = selectionFilters(selectionQuery(params));
    for (const key of ["pagesFrom", "pagesTo"]) {
      const expected = new URLSearchParams(query).get(key);
      expect(request.get(key)).toBe(expected);
      expect(selected[key]).toBe(
        expected === null ? undefined : Number(expected),
      );
    }
  }
});

it("accepts EPL ID lists without changing revision or text separators", () => {
  expect(normalizeFilterValues("eplId", "123, 456;789\n123\t00042")).toEqual([
    "123",
    "456",
    "789",
    "42",
  ]);
  for (const invalid of [
    "1, abc, 2",
    "1;0",
    "1 -2",
    "1 2.5",
    "9007199254740992",
  ])
    expect(normalizeFilterValues("eplId", invalid)).toBeNull();
  expect(normalizeFilterValues("eplId", "")).toEqual([]);
  expect(normalizeFilterValues("revision", "1,2")).toEqual(["1.2"]);
  expect(normalizeFilterValues("author", "Author One; Author Two")).toEqual([
    "Author One; Author Two",
  ]);
});

it("keeps library criteria in requests and selections and normalizes revision presence", async () => {
  const { catalogParams } = await import("../../api/catalog");
  const { selectionQuery, selectionFilters } =
    await import("./CatalogSelection");
  const params = catalogParams(
    new URLSearchParams("torrentRevision=UPDATED&calibrePresence=ABSENT"),
  );
  expect(filterRequest(params).get("torrentPresence")).toBe("PRESENT");
  expect(selectionFilters(selectionQuery(params))).toMatchObject({
    torrentPresence: "PRESENT",
    torrentRevision: "UPDATED",
    calibrePresence: "ABSENT",
  });
  const absent = filterRequest(
    new URLSearchParams(
      "torrentPresence=ABSENT&torrentRevision=OUTDATED&calibrePresence=ABSENT&calibreRevision=UPDATED",
    ),
  );
  expect(absent.has("torrentRevision")).toBe(false);
  expect(absent.has("calibreRevision")).toBe(false);
  expect(
    filterRequest(catalogParams(new URLSearchParams())).has("torrentPresence"),
  ).toBe(false);
});

it("preserves multiple torrent states in selections and clears them for absent torrents", async () => {
  const { catalogParams } = await import("../../api/catalog");
  const { selectionQuery, selectionFilters } = await import("./CatalogSelection");
  const params = catalogParams(new URLSearchParams("torrentStatus=DOWNLOADED&torrentStatus=QUEUED"));
  expect(params.getAll("torrentStatus")).toEqual(["DOWNLOADED", "QUEUED"]);
  expect(selectionFilters(selectionQuery(params))).toMatchObject({
    torrentStatus: ["DOWNLOADED", "QUEUED"], torrentPresence: "PRESENT",
  });
  expect(filterRequest(new URLSearchParams("torrentPresence=ABSENT&torrentStatus=DOWNLOADED")).has("torrentStatus")).toBe(false);
});

it("preserves every state operator for select-all and omits operators without values", async () => {
  const { catalogParams } = await import("../../api/catalog");
  const { selectionQuery, selectionFilters } = await import("./CatalogSelection");
  const params=catalogParams(new URLSearchParams("status=VERIFICADO&statusOperator=ne&publicationStatus=UPDATED&publicationStatusOperator=ne&torrentStatus=DOWNLOADED&torrentStatusOperator=ne"));
  expect(selectionFilters(selectionQuery(params))).toMatchObject({status:["VERIFICADO"],statusOperator:"ne",publicationStatus:["UPDATED"],publicationStatusOperator:"ne",torrentStatus:["DOWNLOADED"],torrentStatusOperator:"ne"});
  expect([...filterRequest(new URLSearchParams("statusOperator=ne&publicationStatusOperator=ne&torrentStatusOperator=ne")).keys()]).toEqual([]);
  expect(filterRequest(new URLSearchParams("torrentPresence=ABSENT&torrentStatus=DOWNLOADED&torrentStatusOperator=ne")).has("torrentStatusOperator")).toBe(false);
});

it("keeps language and negative text criteria for every selected result", async () => {
  const {catalogParams}=await import("../../api/catalog");
  const {selectionQuery,selectionFilters}=await import("./CatalogSelection");
  const params=catalogParams(new URLSearchParams("title=Dune&title=Foundation&titleOperator=ne&author=Herbert&authorOperator=ne&language=es&languageOperator=ne"));
  expect(selectionFilters(selectionQuery(params))).toMatchObject({title:["Dune","Foundation"],titleOperator:"ne",author:["Herbert"],authorOperator:"ne",language:["es"],languageOperator:"ne"});
  expect([...filterRequest(new URLSearchParams("titleOperator=ne&authorOperator=ne&languageOperator=ne")).keys()]).toEqual([]);
});

it("clicking a value replaces a negative field while preserving other filters", async () => {
  const {quickFilter}=await import("./CatalogValue");
  const params=quickFilter(new URLSearchParams("author=Asimov&authorOperator=ne&language=es&languageOperator=ne"),"author","Herbert");
  expect(params.getAll("author")).toEqual(["Herbert"]);
  expect(params.has("authorOperator")).toBe(false);
  expect(params.get("languageOperator")).toBe("ne");
});
