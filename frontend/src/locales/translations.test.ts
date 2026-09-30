import { describe, it, expect } from "vitest";
import { createInstance } from "i18next";
import es from "./es.json";
import { locales, resources } from "./resources";
function flatten(
  value: Record<string, unknown>,
  prefix = "",
): Record<string, string> {
  return Object.fromEntries(
    Object.entries(value).flatMap(([key, item]) =>
      typeof item === "string"
        ? [[prefix + key, item]]
        : Object.entries(
            flatten(item as Record<string, unknown>, prefix + key + "."),
          ),
    ),
  );
}
const pluralSuffix = /_(zero|one|two|few|many|other)$/;
const baseKey = (key: string) => key.replace(pluralSuffix, "");
describe("translation contract", () => {
  it("every locale has the same messages and interpolation variables", () => {
    const base = flatten(es);
    for (const { translation } of Object.values(locales)) {
      const candidate = flatten(translation);
      expect([...new Set(Object.keys(candidate).map(baseKey))].sort()).toEqual(
        [...new Set(Object.keys(base).map(baseKey))].sort(),
      );
      for (const [key, value] of Object.entries(candidate)) {
        expect(value.trim(), key).not.toBe("");
        const original = base[key] ?? base[baseKey(key) + "_other"];
        expect(value.match(/{{[^}]+}}/g)?.sort() ?? [], key).toEqual(
          original.match(/{{[^}]+}}/g)?.sort() ?? [],
        );
        if (pluralSuffix.test(key))
          expect(candidate[baseKey(key) + "_other"], key).toBeTruthy();
      }
    }
  });
  it("uses translated singular and plural counts, with an English fallback", async () => {
    const instance = createInstance();
    await instance.init({ resources, lng: "en", fallbackLng: "en" });
    expect(
      instance.t("catalog.results", { count: 1, formattedCount: "1" }),
    ).toBe("1 book found");
    expect(
      instance.t("catalog.results", { count: 0, formattedCount: "0" }),
    ).toBe("0 books found");
    await instance.changeLanguage("es");
    expect(
      instance.t("catalog.results", { count: 1, formattedCount: "1" }),
    ).toBe("1 libro encontrado");
    await instance.changeLanguage("fr");
    expect(instance.t("nav.home")).toBe("Home");
  });
});
