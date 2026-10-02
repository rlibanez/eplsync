import { useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useNotifications } from "../notifications/Notifications";
import { type AppEvent, eventHref, eventTone } from "./eventTypes";

export function EventConnection() {
  const { notify } = useNotifications();
  const { t } = useTranslation();
  const translate = useRef(t);
  translate.current = t;
  const cache = useQueryClient();
  useEffect(() => {
    let cursor = 0;
    let lost = false;
    let warning: number | undefined;
    let epoch = Date.now();
    const channel = new EventSource("/api/events/stream");
    const publish = (deleted = false) => {
      cache.setQueryData<{ cursor: number; revision: number }>(
        ["event-changes"],
        (previous) => ({
          cursor,
          revision: (previous?.revision ?? 0) + (deleted ? 1 : 0),
        }),
      );
    };
    const refresh = () => {
      void cache.invalidateQueries({ queryKey: ["events"] });
      void cache.invalidateQueries({ queryKey: ["event-unread"] });
    };
    channel.addEventListener("ready", (message) => {
      const data = JSON.parse((message as MessageEvent).data);
      cursor = Math.max(cursor, data.cursor);
      cache.setQueryData(["event-stream"], "connected");
      publish();
      refresh(); // Also close the race between the initial history request and the stream handshake.
      lost = false;
      window.clearTimeout(warning);
    });
    channel.addEventListener("reset", (message) => {
      cursor = JSON.parse((message as MessageEvent).data).cursor;
      epoch++;
      cache.setQueryData(["event-stream"], "connected");
      lost = false;
      window.clearTimeout(warning);
      publish(true);
      refresh();
    });
    channel.addEventListener("database-reset", () => {
      publish(true);
      void cache.invalidateQueries();
    });
    channel.addEventListener("refresh", () => {
      publish(true);
      refresh();
    });
    channel.addEventListener("event", (message) => {
      const event: AppEvent = JSON.parse((message as MessageEvent).data);
      if (!Number.isSafeInteger(event.id) || event.id <= cursor) return;
      cursor = event.id;
      publish();
      notify({
        id: `server-event:${epoch}:${event.id}`,
        title: translate.current("events.actions." + event.action),
        message:
          event.action === "SEND_BOOK" && event.outcome === "SUCCEEDED"
            ? translate.current(
                event.details.submissionStatus === "ALREADY_EXISTS"
                  ? "detail.alreadySent"
                  : "events.bookAccepted",
                { eplId: event.details.eplId },
              )
            : event.category === "CATALOG" &&
                event.action === "PREVIEW" &&
                event.outcome === "STARTED"
              ? translate.current("import.pendingDescription")
              : event.category === "CATALOG" &&
                  event.action === "PREVIEW" &&
                  event.outcome === "SUCCEEDED"
                ? translate.current("import.previewDone")
                : event.category === "CATALOG" &&
                    event.outcome === "STARTED" &&
                    ["UPDATE", "REPLACE"].includes(event.action)
                  ? translate.current(
                      event.details.retainedZip
                        ? "import.applyPending"
                        : "import.updateStarted",
                    )
                  : translate.current("events.outcomes." + event.outcome),
        tone: eventTone(event),
        phase: ["STARTED", "RESUMED", "RECOVERED"].includes(event.outcome)
          ? "start"
          : "result",
        href: eventHref(event),
      });
      refresh();
      if (event.outcome !== "STARTED") {
        const keys =
          event.category === "CATALOG"
            ? ["catalog", "book", "catalog-metadata", "directory"]
            : event.category === "COVERS"
              ? ["cover-task", "catalog", "book"]
              : [
                  "jobs",
                  "job",
                  "job-items",
                  "downloads",
                  "download-summary",
                  "catalog",
                  "book",
                ];
        keys.forEach((key) => {
          void cache.invalidateQueries({ queryKey: [key] });
        });
      }
    });
    channel.onerror = () => {
      cache.setQueryData(["event-stream"], "reconnecting");
      if (!lost)
        warning = window.setTimeout(
          () =>
            notify({
              title: translate.current("events.title"),
              message: translate.current("events.reconnecting"),
              tone: "warning",
            }),
          5000,
        );
      lost = true;
    };
    return () => {
      window.clearTimeout(warning);
      channel.close();
    };
  }, [cache, notify]);
  return null;
}
