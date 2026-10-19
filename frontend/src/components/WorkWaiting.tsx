import { useTranslation } from "react-i18next";

/** Describes the worker's current behaviour; never changes or retries work. */
export function WorkWaiting({
  state,
  jobState,
  intervention,
  hideAutomatic = false,
}: {
  state: string;
  jobState?: string;
  intervention?: boolean | null;
  hideAutomatic?: boolean;
}) {
  const { t } = useTranslation();
  const mode = workWaitingMode(state, jobState, intervention);
  if (!mode || (hideAutomatic && mode === "automatic")) return null;
  return <p className="muted work-waiting">{t(`workWaiting.${mode}`)}</p>;
}

export function workWaitingMode(
  state: string,
  jobState?: string,
  intervention?: boolean | null,
): "automatic" | "intervention" | null {
  const waiting = [
    "PENDING",
    "WAITING_DOWNLOAD",
    "CLEANUP_PENDING",
    "VERIFYING",
    "UNCERTAIN",
    "BLOCKED",
    "BLOCKED_DOWNLOAD",
    "FAILED",
  ].includes(state);
  if (!waiting) return null;
  const manual =
    ["PAUSED", "CANCELLED"].includes(jobState ?? "") ||
    (intervention ??
      (![
        "QUEUED",
        "RUNNING",
        "RETRY_WAIT",
        "DOWNLOAD_WAIT",
        "CLEANUP_WAIT",
      ].includes(jobState ?? "") ||
        ["BLOCKED_DOWNLOAD", "FAILED", "BLOCKED"].includes(state) ||
        (state === "UNCERTAIN" &&
          !["QUEUED", "RUNNING"].includes(jobState ?? ""))));
  return manual ? "intervention" : "automatic";
}
