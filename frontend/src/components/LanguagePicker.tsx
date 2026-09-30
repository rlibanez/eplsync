import { useId } from "react";
import { useTranslation } from "react-i18next";
import { chooseLanguage } from "../locales/i18n";
import { supportedLanguages } from "../locales/resources";
export function LanguagePicker() {
  const id = useId();
  const { t, i18n } = useTranslation();
  return (
    <div className="language-picker">
      <label className="sr-only" htmlFor={id}>
        {t("nav.language")}
      </label>
      <select
        id={id}
        value={i18n.resolvedLanguage}
        onChange={(event) => void chooseLanguage(event.target.value)}
      >
        {supportedLanguages.map((language) => (
          <option key={language.value} value={language.value}>
            {language.label}
          </option>
        ))}
      </select>
    </div>
  );
}
