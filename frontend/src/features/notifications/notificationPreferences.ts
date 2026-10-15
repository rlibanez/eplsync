export const notificationDefaults = {
  floating: true,
  unreadBadge: true,
  starts: true,
  finishes: true,
  success: true,
  warning: true,
  error: true,
  seconds: 5,
};
export type NotificationPreferences = typeof notificationDefaults;
export function parseNotificationPreferences(
  raw: string,
): NotificationPreferences {
  const result = { ...notificationDefaults };
  try {
    const parsed = JSON.parse(raw);
    for (const field of [
      "floating",
      "unreadBadge",
      "starts",
      "finishes",
      "success",
      "warning",
      "error",
    ] as const)
      if (typeof parsed?.[field] === "boolean") result[field] = parsed[field];
    if (typeof parsed?.seconds === "number" && Number.isFinite(parsed.seconds))
      result.seconds = Math.max(1, Math.min(60, parsed.seconds));
  } catch {
    /* Use defaults for missing or invalid preferences. */
  }
  return result;
}
export function allowsNotification(
  preferences: NotificationPreferences,
  tone: "success" | "error" | "warning" | "info",
  phase: "start" | "result" = "result",
) {
  if (!preferences.floating) return false;
  if (phase === "start") return preferences.starts;
  return (
    preferences.finishes && preferences[tone === "info" ? "success" : tone]
  );
}
