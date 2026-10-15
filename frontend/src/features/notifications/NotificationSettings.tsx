import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/Auth";
import { authRequest } from "../auth/transport";
import { Failure, Loading } from "../../components/Feedback";
import { useEventDisplayPreferences } from "./eventDisplayPreferences";
import type { NotificationPreferences } from "./notificationPreferences";
import { Checkbox, Slider, NumberInput } from "@mantine/core";
import { BellRing, Clock3, SlidersHorizontal } from "lucide-react";
import { useTranslation } from "react-i18next";

export function NotificationSettings() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const query = useEventDisplayPreferences();
  const cache = useQueryClient();
  const [duration, setDuration] = useState<number | string | null>(null);
  const mutation = useMutation({
    mutationFn: (value: NotificationPreferences) =>
      authRequest<NotificationPreferences>("/auth/notifications", "PUT", value),
    onMutate: (value) => {
      const key = ["event-display-preferences", user?.id];
      const previous = cache.getQueryData<NotificationPreferences>(key);
      void cache.cancelQueries({ queryKey: key });
      cache.setQueryData(key, value);
      return { accountId: user?.id, previous };
    },
    onError: (_error, _value, context) => {
      if (context?.previous)
        cache.setQueryData(
          ["event-display-preferences", context.accountId],
          context.previous,
        );
    },
    onSuccess: (value, _variables, context) =>
      cache.setQueryData(
        ["event-display-preferences", context?.accountId],
        value,
      ),
    onSettled: () => setDuration(null),
  });
  if (query.isPending) return <Loading />;
  if (query.isError)
    return <Failure error={query.error} retry={() => query.refetch()} />;
  const preferences =
    mutation.isPending && mutation.variables ? mutation.variables : query.data;
  const disabled = mutation.isPending || !preferences.floating;
  const save = (patch: Partial<NotificationPreferences>) =>
    mutation.mutate({ ...preferences, ...patch });
  return (
    <section className="panel settings-section notification-settings">
      <h2>{t("notifications.title")}</h2>
      <div className="notification-checkboxes">
        <Checkbox
          label={t("notifications.floating")}
          checked={preferences.floating}
          disabled={mutation.isPending}
          onChange={(e) => save({ floating: e.currentTarget.checked })}
        />
        <Checkbox
          label={t("notifications.unreadBadge")}
          checked={preferences.unreadBadge}
          disabled={mutation.isPending}
          onChange={(e) => save({ unreadBadge: e.currentTarget.checked })}
        />
      </div>
      <div className="notification-preferences-grid settings-fields-grid">
        <div className="notification-preference-block">
          <h3>
            <BellRing size={18} aria-hidden="true" />
            {t("notifications.when")}
          </h3>
          <div className="notification-checkboxes">
            <Checkbox
              label={t("notifications.starts")}
              disabled={disabled}
              checked={preferences.starts}
              onChange={(e) => save({ starts: e.currentTarget.checked })}
            />
            <Checkbox
              label={t("notifications.finishes")}
              disabled={disabled}
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
            className="notification-checkboxes"
            role="group"
            aria-labelledby="notification-results-label"
          >
            {(["success", "warning", "error"] as const).map((field) => (
              <Checkbox
                key={field}
                label={t("notifications." + field)}
                checked={preferences[field]}
                disabled={disabled || !preferences.finishes}
                onChange={(e) => save({ [field]: e.currentTarget.checked })}
              />
            ))}
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
              disabled={disabled}
              value={
                typeof duration === "number" ? duration : preferences.seconds
              }
              onChange={setDuration}
              onChangeEnd={(seconds) => save({ seconds })}
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
              disabled={disabled}
              value={duration ?? preferences.seconds}
              onChange={setDuration}
              onBlur={() => {
                if (typeof duration === "number")
                  save({ seconds: Math.max(1, Math.min(60, duration)) });
                else setDuration(null);
              }}
            />
          </div>
        </div>
      </div>
      {mutation.isError && (
        <Failure
          error={mutation.error}
          retry={() =>
            mutation.variables && mutation.mutate(mutation.variables)
          }
        />
      )}
    </section>
  );
}
