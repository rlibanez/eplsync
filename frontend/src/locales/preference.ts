import { supportedLanguages } from "./resources";
export const languageStorageKey = "eplsync:language";
export function resolveLanguage(
  value: unknown,
  browserLanguages: readonly string[] = [],
): string {
  if (value === "auto" || value === undefined || value === null) {
    for (const candidate of browserLanguages) {
      const base = candidate.toLowerCase().split(/[-_]/)[0];
      if (supportedLanguages.some((language) => language.value === base))
        return base;
    }
    return "en";
  }
  const base =
    typeof value === "string"
      ? value.trim().toLowerCase().split(/[-_]/)[0]
      : "";
  return supportedLanguages.some((language) => language.value === base)
    ? base
    : "en";
}
export function initialLanguage(serverLanguage: string): string {
  try {
    const saved = localStorage.getItem(languageStorageKey);
    if (
      saved &&
      supportedLanguages.some((language) => language.value === saved)
    )
      return saved;
  } catch {
    /* Browser storage is optional. */
  }
  return serverLanguage;
}
