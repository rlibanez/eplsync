import { useEffect, useState } from "react";
import { useMutationState } from "@tanstack/react-query";
import { Alert, Button } from "@mantine/core";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
export function TorrentActivity() {
  const { t } = useTranslation();
  const location = useLocation();
  const [dismissed, setDismissed] = useState(0);
  const mutations = useMutationState({
    filters: { mutationKey: ["send-books"] },
    select: (m) => ({
      status: m.state.status,
      started: m.state.submittedAt,
      feedbackPath: m.meta?.feedbackPath,
    }),
  });
  const latest = mutations.at(-1);
  const pending = latest?.status === "pending";
  useEffect(() => {
    if (!pending) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [pending]);
  if (
    !latest ||
    latest.started === dismissed ||
    latest.feedbackPath === location.pathname ||
    location.pathname.startsWith("/downloads/send")
  )
    return null;
  return (
    <Alert
      className="torrent-activity"
      color={pending ? "blue" : latest.status === "error" ? "red" : "green"}
      role="status"
    >
      {t(
        pending
          ? "send.running"
          : latest.status === "error"
            ? "downloads.uncertain"
            : "send.finished",
      )}{" "}
      <Link to="/downloads">{t("nav.downloadState")}</Link> ·{" "}
      <Link to="/downloads/jobs">{t("nav.jobs")}</Link>
      {!pending && (
        <Button
          variant="subtle"
          size="xs"
          onClick={() => setDismissed(latest.started)}
        >
          {t("send.dismiss")}
        </Button>
      )}
    </Alert>
  );
}
