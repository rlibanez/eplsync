import { formatDate } from "./formatDate";
import { useTranslation } from "react-i18next";
export function useLocale() {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? "es";
  return {
    revision: (value: number | string | null | undefined) =>
      value == null ? "—" : String(value),
    number: (value: number | null, options?: Intl.NumberFormatOptions) =>
      value === null
        ? "—"
        : new Intl.NumberFormat(locale, options).format(value),
    date: (value: string | null) => formatDate(value, locale),
    language: (value: string | null) =>
      value
        ? t(`languages.${value}`, { defaultValue: value })
        : t("detail.unknownLanguage"),
    status: (value: string | null) =>
      value ? t(`statuses.${value}`, { defaultValue: value }) : "—",
  };
}
