import { expect, it } from "vitest";
import {
  applyDiscoveryConfiguration,
  discoveryConfigurationSignature,
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
it("omits inactive values, rejects empty criteria and preserves an explicitly empty selection", () => {
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
  ).toBe(true);
  expect(params.getAll("discoveryValues")).toEqual(["GENRE:"]);
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

it("preserves author detection exclusions through search and export", () => {
  const params = new URLSearchParams();
  expect(
    applyDiscoveryConfiguration(params, {
      ...config,
      ignoreAnthologies: true,
      maxAuthors: 2,
    }),
  ).toBe(true);
  const filters = selectionFilters(selectionQuery(catalogParams(params)));
  expect(filters.discoveryIgnoreAnthologies).toBe("true");
  expect(filters.discoveryMaxAuthors).toBe("2");
  expect(applyDiscoveryConfiguration(params, config)).toBe(true);
  expect(params.has("discoveryIgnoreAnthologies")).toBe(false);
  expect(params.has("discoveryMaxAuthors")).toBe(false);
  expect(
    applyDiscoveryConfiguration(params, { ...config, maxAuthors: 0 }),
  ).toBe(false);
});

it("compares discovery drafts without treating inactive values or selection order as changes", () => {
  expect(
    discoveryConfigurationSignature({
      ...config,
      criteria: [...config.criteria].reverse(),
    }),
  ).toBe(discoveryConfigurationSignature(config));
  expect(
    discoveryConfigurationSignature({
      ...config,
      choices: { ...config.choices, AUTHOR: { all: true, values: ["unused"] } },
    }),
  ).toBe(discoveryConfigurationSignature(config));
  expect(
    discoveryConfigurationSignature({ ...config, maxAuthors: 2 }),
  ).not.toBe(discoveryConfigurationSignature(config));
  expect(discoveryConfigurationSignature({ ...config, criteria: [] })).not.toBe(
    discoveryConfigurationSignature(config),
  );
});

it("preserves all-except selections through saved search and export without enumerating the vocabulary", () => {
  const params = new URLSearchParams();
  expect(
    applyDiscoveryConfiguration(params, {
      ...config,
      criteria: ["AUTHOR"],
      choices: {
        AUTHOR: { all: true, values: [], excluded: ["autor a", "autor b"] },
      },
    }),
  ).toBe(true);
  expect(params.getAll("discoveryValues")).toEqual([]);
  expect(params.getAll("discoveryExcludedValues")).toEqual([
    "AUTHOR:autor a",
    "AUTHOR:autor b",
  ]);
  const filters = selectionFilters(selectionQuery(catalogParams(params)));
  expect(filters.discoveryExcludedValues).toEqual([
    "AUTHOR:autor a",
    "AUTHOR:autor b",
  ]);
});
