import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { resources, supportedLanguages } from "./resources";
import {
  initialLanguage,
  languageStorageKey,
  resolveBrowserLanguage,
} from "./preference";
export async function initializeI18n() {
  await i18n.use(initReactI18next).init({
    resources,
    lng: initialLanguage(
      resolveBrowserLanguage(
        navigator.languages.length ? navigator.languages : [navigator.language],
      ),
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
