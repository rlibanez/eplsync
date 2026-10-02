import { useSyncExternalStore } from "react";
import { Switch, Slider, NumberInput } from "@mantine/core";
import {
  BellRing,
  Check,
  CircleCheck,
  TriangleAlert,
  CircleX,
  Clock3,
  SlidersHorizontal,
} from "lucide-react";
import { useTranslation } from "react-i18next";

export const notificationDefaults = {
  starts: false,
  finishes: true,
  success: true,
  warning: true,
  error: true,
  seconds: 6,
};
export type NotificationPreferences = typeof notificationDefaults;
const key = "eplsync.notificationPreferences";
const changed = "eplsync-notification-preferences";
let fallback = "";
function snapshot() {
  try {
    return localStorage.getItem(key) || fallback;
  } catch {
    return fallback;
  }
}
function subscribe(listener: () => void) {
  window.addEventListener(changed, listener);
  window.addEventListener("storage", listener);
  return () => {
    window.removeEventListener(changed, listener);
    window.removeEventListener("storage", listener);
  };
}
export function parseNotificationPreferences(
  raw: string,
): NotificationPreferences {
  const result = { ...notificationDefaults };
  try {
    const parsed = JSON.parse(raw);
    for (const field of [
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
export const readNotificationPreferences = () =>
  parseNotificationPreferences(snapshot());
export function allowsNotification(
  preferences: NotificationPreferences,
  tone: "success" | "error" | "warning" | "info",
  phase: "start" | "result" = "result",
) {
  if (phase === "start") return preferences.starts;
  return (
    preferences.finishes && preferences[tone === "info" ? "success" : tone]
  );
}
export function NotificationSettings() {
  const { t } = useTranslation();
  const raw = useSyncExternalStore(subscribe, snapshot, () => "");
  const preferences = parseNotificationPreferences(raw);
  const save = (patch: Partial<NotificationPreferences>) => {
    fallback = JSON.stringify({ ...preferences, ...patch });
    try {
      localStorage.setItem(key, fallback);
    } catch {
      /* Browser preference only. */
    }
    window.dispatchEvent(new Event(changed));
  };
  return (
    <section className="panel settings-section notification-settings">
      <h2>{t("notifications.title")}</h2>
      <p className="muted">{t("notifications.preferencesHint")}</p>
      <div className="notification-preferences-grid">
        <div className="notification-preference-block">
          <h3>
            <BellRing size={18} aria-hidden="true" />
            {t("notifications.when")}
          </h3>
          <div className="notification-switches">
            <Switch
              label={t("notifications.starts")}
              checked={preferences.starts}
              onChange={(e) => save({ starts: e.currentTarget.checked })}
            />
            <Switch
              label={t("notifications.finishes")}
              checked={preferences.finishes}
              onChange={(e) => save({ finishes: e.currentTarget.checked })}
            />
          </div>
        </div>
        <div className="notification-preference-block">
          <h3 id="notification-results-label">
            <SlidersHorizontal size={18} aria-hidden="true" />
            {t("notifications.results")}
          </h3>
          <div
            className="notification-result-options"
            role="group"
            aria-labelledby="notification-results-label"
          >
            {(["success", "warning", "error"] as const).map((field) => {
              const Icon = {
                success: CircleCheck,
                warning: TriangleAlert,
                error: CircleX,
              }[field];
              return (
                <label key={field} className="notification-result-option">
                  <input
                    type="checkbox"
                    className="notification-choice-input"
                    checked={preferences[field]}
                    disabled={!preferences.finishes}
                    onChange={(e) => save({ [field]: e.currentTarget.checked })}
                  />
                  <span>
                    <Icon size={17} aria-hidden="true" />
                    {t("notifications." + field)}
                    <Check
                      className="notification-option-check"
                      size={15}
                      aria-hidden="true"
                    />
                  </span>
                </label>
              );
            })}
          </div>
        </div>
        <div className="notification-preference-block">
          <h3>
            <Clock3 size={18} aria-hidden="true" />
            {t("notifications.duration")}
          </h3>
          <div className="notification-duration">
            <Slider
              min={1}
              max={60}
              step={1}
              value={preferences.seconds}
              onChange={(seconds) => save({ seconds })}
              aria-label={t("notifications.seconds")}
              label={(value) => value + " s"}
              marks={[
                { value: 1, label: "1 s" },
                { value: 30, label: "30 s" },
                { value: 60, label: "60 s" },
              ]}
            />
            <NumberInput
              aria-label={t("notifications.seconds")}
              min={1}
              max={60}
              step={1}
              allowDecimal={false}
              hideControls
              suffix=" s"
              value={preferences.seconds}
              onChange={(value) => {
                if (typeof value === "number")
                  save({ seconds: Math.max(1, Math.min(60, value)) });
              }}
            />
          </div>
          <p className="muted notification-duration-note">
            {t("notifications.durationHint")}
          </p>
        </div>
      </div>
    </section>
  );
}
