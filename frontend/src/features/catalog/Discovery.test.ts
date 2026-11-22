import { expect, it } from "vitest";
import {
  applyDiscoveryConfiguration,
  type DiscoveryPreferences,
} from "./Discovery";
import { catalogParams } from "../../api/catalog";
import { selectionFilters, selectionQuery } from "./CatalogSelection";
const config: DiscoveryPreferences = {
  source: "BOTH",
  criteria: ["AUTHOR", "GENRE", "LANGUAGE", "YEAR"],
  match: "ANY",
  choices: {
    AUTHOR: { all: true, values: [] },
    GENRE: { all: false, values: ["fantasía", "aventura"] },
    LANGUAGE: { all: false, values: ["es"] },
  },
  downloads: true,
  calibre: true,
  pending: true,
};
it("preserves combined discovery criteria, mode and scoped values through selection/export", () => {
  const params = new URLSearchParams(
    "torrentPresence=ABSENT&calibrePresence=ABSENT&discoveryCriterion=AUTHOR&discoveryValues=old",
  );
  expect(applyDiscoveryConfiguration(params, config)).toBe(true);
  const parsed = catalogParams(params);
  expect(parsed.getAll("discoveryCriteria")).toEqual(config.criteria);
  expect(parsed.get("discoveryMatch")).toBe("ANY");
  expect(parsed.has("discoveryCriterion")).toBe(false);
  expect(parsed.getAll("discoveryValues")).toEqual([
    "GENRE:fantasía",
    "GENRE:aventura",
    "LANGUAGE:es",
  ]);
  const filters = selectionFilters(selectionQuery(parsed));
  expect(filters.discoveryCriteria).toEqual(config.criteria);
  expect(filters.discoveryValues).toEqual([
    "GENRE:fantasía",
    "GENRE:aventura",
    "LANGUAGE:es",
  ]);
  expect(filters.discoveryMatch).toBe("ANY");
  expect(filters.torrentPresence).toBe("ABSENT");
});
it("omits values for inactive criteria and rejects empty criteria or empty manual selections", () => {
  const params = new URLSearchParams();
  expect(
    applyDiscoveryConfiguration(params, { ...config, criteria: ["AUTHOR"] }),
  ).toBe(true);
  expect(params.has("discoveryValues")).toBe(false);
  expect(applyDiscoveryConfiguration(params, { ...config, criteria: [] })).toBe(
    false,
  );
  expect(
    applyDiscoveryConfiguration(params, {
      ...config,
      choices: { GENRE: { all: false, values: [] } },
    }),
  ).toBe(false);
});

it("serializes restored criteria and their values in dropdown order", () => {
  const params = new URLSearchParams();
  expect(
    applyDiscoveryConfiguration(params, {
      ...config,
      criteria: ["YEAR", "LANGUAGE", "GENRE", "COLLECTION", "AUTHOR", "AUTHOR"],
    }),
  ).toBe(true);
  expect(params.getAll("discoveryCriteria")).toEqual([
    "AUTHOR",
    "COLLECTION",
    "GENRE",
    "LANGUAGE",
    "YEAR",
  ]);
  expect(params.getAll("discoveryValues")).toEqual([
    "GENRE:fantasía",
    "GENRE:aventura",
    "LANGUAGE:es",
  ]);
});
