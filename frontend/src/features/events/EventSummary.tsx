import { Link } from "react-router-dom";
import { useAuth } from "../auth/Auth";
import { copyDetails } from "./copyDetails";
import { useState } from "react";
import { Button } from "@mantine/core";
import { useTranslation } from "react-i18next";
import {
  type AppEvent,
  type EventOperation,
  formatDuration,
  eventActorLabel,
} from "./eventTypes";

export function EventSummary({ operation }: { operation: EventOperation }) {
  const { t, i18n } = useTranslation();
  const { can } = useAuth();
  const canViewDownload = can("TORRENT_JOBS_MANAGE");
  const canViewCalibre = can("CALIBRE_SEND") || can("CALIBRE_JOBS_MANAGE");
  const jobLink = (
    event: AppEvent,
    field: "operationId" | "jobId" | "downloadJobId",
  ) => {
    const id =
      field === "operationId" ? event.operationId : event.details[field];
    if (typeof id !== "string" || !id || id.startsWith("manual:")) return null;
    const encoded = encodeURIComponent(id);

    if (field === "downloadJobId")
      return event.action === "CALIBRE_JOB_CONTROL" && canViewDownload
        ? `/downloads/jobs/${encoded}`
        : null;

    switch (event.action) {
      case "DOWNLOAD":
        return field === "operationId" && canViewDownload
          ? `/downloads/jobs/${encoded}`
          : null;
      case "CLEANUP":
        return field === "jobId" && canViewDownload
          ? `/downloads/jobs/${encoded}`
          : null;
      case "CALIBRE_SEND":
        return field === "operationId" && canViewCalibre
          ? `/calibre/jobs/${encoded}`
          : null;
      case "CALIBRE_JOB_CONTROL":
        return field === "jobId" && canViewCalibre
          ? `/calibre/jobs/${encoded}`
          : null;
      case "REVISION_CLEANUP":
      case "REVISION_CLEANUP_RETRY": {
        if (
          field !==
          (event.action === "REVISION_CLEANUP" ? "operationId" : "jobId")
        )
          return null;
        const source = event.details.source;
        if (source === "downloads" && can("TORRENT_SYNC"))
          return `/cleanup/jobs/${encoded}`;
        if (source === "calibre" && can("CALIBRE_READ"))
          return `/cleanup/jobs/${encoded}`;
        return null;
      }
      default:
        return null;
    }
  };
  const jobHref = jobLink(operation.latest, "operationId");
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
    if (key === "taskOperation") return t("scheduledTasks.operations." + value);
    if (key === "submissionStatus") return t("statuses." + value);
    if (typeof value === "boolean")
      return t(value ? "downloads.yes" : "downloads.no");
    if (typeof value === "number")
      return key.endsWith("Ms")
        ? formatDuration(value, i18n.resolvedLanguage)
        : number.format(value);
    return value;
  };
  const visibleDetails = (event: AppEvent) =>
    Object.entries(event.details).filter(
      ([key]) =>
        key !== "dryRun" ||
        (event.action !== "SYNC" && event.category !== "CATALOG"),
    );
  const copy = async () => {
    const text = [
      t("events.operation") + ": " + operation.latest.operationId,
      ...operation.events.flatMap((event) => [
        date(event.createdAt) + " — " + t("events.outcomes." + event.outcome),
        t("events.user") + ": " + eventActorLabel(event, t),
        ...visibleDetails(event).map(
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
        {t("events.operation")}:{" "}
        {jobHref ? (
          <Link className="event-job-link" to={jobHref}>
            {operation.latest.operationId}
          </Link>
        ) : (
          operation.latest.operationId
        )}
      </p>
      {!operation.startedAt && (
        <p className="muted">{t("events.missingStart")}</p>
      )}
      <ol className="event-timeline">
        {operation.events.map((event) => (
          <li key={event.id}>
            <strong>{t("events.outcomes." + event.outcome)}</strong>
            <time dateTime={event.createdAt}>{date(event.createdAt)}</time>
            <p className="muted">
              {t("events.user")}: {eventActorLabel(event, t)}
            </p>
            {!!visibleDetails(event).length && (
              <dl className="event-details">
                {visibleDetails(event).map(([key, value]) => {
                  const href =
                    key === "jobId" || key === "downloadJobId"
                      ? jobLink(event, key)
                      : null;
                  return (
                    <div key={key}>
                      <dt>
                        {t("events.fields." + key, { defaultValue: key })}
                      </dt>
                      <dd>
                        {href ? (
                          <Link className="event-job-link" to={href}>
                            {String(value)}
                          </Link>
                        ) : (
                          valueLabel(key, value)
                        )}
                      </dd>
                    </div>
                  );
                })}
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
