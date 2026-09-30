import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { resources, supportedLanguages } from "./resources";
import {
  initialLanguage,
  languageStorageKey,
  resolveLanguage,
} from "./preference";
let deploymentLanguage: unknown = "auto";
export async function initializeI18n() {
  try {
    const response = await fetch("/api/ui/config", {
      cache: "no-store",
      signal: AbortSignal.timeout(4000),
    });
    if (response.ok)
      deploymentLanguage = (await response.json()).defaultLanguage;
  } catch {
    /* The interface remains usable if runtime configuration is unavailable. */
  }
  await i18n.use(initReactI18next).init({
    resources,
    lng: initialLanguage(
      resolveLanguage(deploymentLanguage, navigator.languages),
    ),
    fallbackLng: "en",
    supportedLngs: supportedLanguages.map((l) => l.value),
    interpolation: { escapeValue: false },
  });
  const updateDocument = (language: string) => {
    document.documentElement.lang = language;
    document.documentElement.dir = i18n.dir(language);
  };
  updateDocument(i18n.resolvedLanguage ?? "en");
  i18n.on("languageChanged", updateDocument);
}
export function chooseLanguage(language: string) {
  try {
    localStorage.setItem(languageStorageKey, language);
  } catch {
    /* Keep the in-memory choice. */
  }
  return i18n.changeLanguage(language);
}
export default i18n;
