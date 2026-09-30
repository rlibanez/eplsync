import { useTranslation } from "react-i18next";
export function useLocale() {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? "es";
  return {
    number: (value: number | null, options?: Intl.NumberFormatOptions) =>
      value === null
        ? "—"
        : new Intl.NumberFormat(locale, options).format(value),
    date: (value: string | null) =>
      value
        ? new Intl.DateTimeFormat(locale, {
            dateStyle: "medium",
            ...(value.includes("T") ? { timeStyle: "short" as const } : {}),
          }).format(new Date(value.includes("T") ? value : `${value}T00:00:00`))
        : "—",
    language: (value: string | null) =>
      value
        ? t(`languages.${value}`, { defaultValue: value })
        : t("detail.unknownLanguage"),
    status: (value: string | null) =>
      value ? t(`statuses.${value}`, { defaultValue: value }) : "—",
  };
}
