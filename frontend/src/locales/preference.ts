import { supportedLanguages } from "./resources";
export const languageStorageKey = "eplsync:language";
export function resolveBrowserLanguage(
  browserLanguages: readonly string[],
): string {
  for (const candidate of browserLanguages) {
    const base = candidate.trim().toLowerCase().split(/[-_]/)[0];
    if (supportedLanguages.some((language) => language.value === base))
      return base;
  }
  return "en";
}
export function initialLanguage(browserLanguage: string): string {
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
  return browserLanguage;
}
