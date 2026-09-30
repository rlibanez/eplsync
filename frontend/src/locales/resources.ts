import es from "./es.json";
import en from "./en.json";

// Register new translations here. Labels use each language's own name.
export const locales = {
  es: { label: "Español", translation: es },
  en: { label: "English", translation: en },
};
export const supportedLanguages = Object.entries(locales).map(
  ([value, locale]) => ({ value, label: locale.label }),
);
export const resources = Object.fromEntries(
  Object.entries(locales).map(([code, locale]) => [
    code,
    { translation: locale.translation },
  ]),
);
