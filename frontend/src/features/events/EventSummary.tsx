import { copyDetails } from "./copyDetails";
import { useState } from "react";
import { Button } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { type EventOperation, formatDuration } from "./eventTypes";

export function EventSummary({ operation }: { operation: EventOperation }) {
  const { t, i18n } = useTranslation();
  const [copyState, setCopyState] = useState<"copy" | "copied" | "copyFailed">(
    "copy",
  );
  const number = new Intl.NumberFormat(i18n.resolvedLanguage);
  const date = (value: string) =>
    new Intl.DateTimeFormat(i18n.resolvedLanguage, {
      dateStyle: "medium",
      timeStyle: "medium",
    }).format(new Date(value));
  const valueLabel = (key: string, value: string | number | boolean) => {
    if (key === "submissionStatus") return t("statuses." + value);
    if (typeof value === "boolean")
      return t(value ? "downloads.yes" : "downloads.no");
    if (typeof value === "number")
      return key.endsWith("Ms")
        ? formatDuration(value, i18n.resolvedLanguage)
        : number.format(value);
    return value;
  };
  const copy = async () => {
    const text = [
      t("events.operation") + ": " + operation.latest.operationId,
      ...operation.events.flatMap((event) => [
        date(event.createdAt) + " — " + t("events.outcomes." + event.outcome),
        ...Object.entries(event.details).map(
          ([key, value]) =>
            t("events.fields." + key, { defaultValue: key }) +
            ": " +
            valueLabel(key, value),
        ),
      ]),
    ].join("\n");
    try {
      await copyDetails(text);
      setCopyState("copied");
    } catch {
      setCopyState("copyFailed");
    }
  };
  return (
    <>
      <p className="muted">
        {t("events.operation")}: {operation.latest.operationId}
      </p>
      {!operation.startedAt && (
        <p className="muted">{t("events.missingStart")}</p>
      )}
      <ol className="event-timeline">
        {operation.events.map((event) => (
          <li key={event.id}>
            <strong>{t("events.outcomes." + event.outcome)}</strong>
            <time dateTime={event.createdAt}>{date(event.createdAt)}</time>
            {!!Object.keys(event.details).length && (
              <dl className="event-details">
                {Object.entries(event.details).map(([key, value]) => (
                  <div key={key}>
                    <dt>{t("events.fields." + key, { defaultValue: key })}</dt>
                    <dd>{valueLabel(key, value)}</dd>
                  </div>
                ))}
              </dl>
            )}
          </li>
        ))}
      </ol>
      <Button variant="subtle" size="xs" onClick={() => void copy()}>
        {t("events.copy")}
      </Button>
      {copyState !== "copy" && (
        <span role="status">{t("events." + copyState)}</span>
      )}
    </>
  );
}
