/** Preserve date-only values; display timestamps with an explicit HH:mm:ss clock. */
export function formatDate(value: string | null, locale: string, timeZone?: string) {
  if (!value) return "—";
  const hasTime = value.includes("T");
  return new Intl.DateTimeFormat(locale, hasTime ? {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
    timeZone,
  } : { dateStyle: "medium", timeZone }).format(
    new Date(hasTime ? value : `${value}T00:00:00`),
  );
}
