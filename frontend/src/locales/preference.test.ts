import { expect, it } from "vitest";
import { resolveLanguage } from "./preference";
it("normalizes a deployment language and falls back to English", () => {
  expect(resolveLanguage("es")).toBe("es");
  expect(resolveLanguage(" ES-es ")).toBe("es");
  expect(resolveLanguage("en_GB")).toBe("en");
  for (const invalid of ["fr", "", null, undefined, 42, "unknown"])
    expect(resolveLanguage(invalid)).toBe("en");
});

it("detects the first available browser language only in auto mode", () => {
  expect(resolveLanguage("auto", ["fr-FR", "es-ES", "en"])).toBe("es");
  expect(resolveLanguage("auto", ["de-DE"])).toBe("en");
  expect(resolveLanguage("en", ["es-ES"])).toBe("en");
  expect(resolveLanguage("unknown", ["es-ES"])).toBe("en");
});
