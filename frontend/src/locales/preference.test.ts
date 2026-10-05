import { afterEach, expect, it, vi } from "vitest";
import { resolveBrowserLanguage, initialLanguage } from "./preference";

afterEach(() => vi.unstubAllGlobals());
it("detects the first supported browser preference, including regional variants", () => {
  expect(resolveBrowserLanguage(["es-ES", "en-US"])).toBe("es");
  expect(resolveBrowserLanguage(["en_GB", "es"])).toBe("en");
  expect(resolveBrowserLanguage(["fr-FR", "es-ES", "en"])).toBe("es");
});
it("falls back to English when none of the browser preferences are supported", () => {
  expect(resolveBrowserLanguage(["de-DE", "fr"])).toBe("en");
  expect(resolveBrowserLanguage([])).toBe("en");
});
it("keeps a valid manual choice but ignores obsolete preferences", () => {
  vi.stubGlobal("localStorage", { getItem: () => "es" });
  expect(initialLanguage("en")).toBe("es");
  vi.stubGlobal("localStorage", { getItem: () => "fr" });
  expect(initialLanguage("en")).toBe("en");
});
it("uses browser detection when storage is missing or inaccessible", () => {
  vi.stubGlobal("localStorage", { getItem: () => null });
  expect(initialLanguage("es")).toBe("es");
  vi.stubGlobal("localStorage", {
    getItem: () => {
      throw new Error("blocked");
    },
  });
  expect(initialLanguage("es")).toBe("es");
});
