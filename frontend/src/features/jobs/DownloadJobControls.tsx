import { useEffect, useState, type ReactNode } from "react";
import { Alert, Button } from "@mantine/core";
import { Pause, Play, CircleStop } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { get } from "../../api/catalog";
import { post, type Job } from "../downloads/shared";
import { useAuth } from "../auth/Auth";

type Ref = { id: string; cleanup: boolean };
type Action = "resume" | "pause" | "cancel";
const active = [
  "QUEUED",
  "RUNNING",
  "RETRY_WAIT",
  "DOWNLOAD_WAIT",
  "CLEANUP_WAIT",
];
const key = (ref: Ref) => `${ref.cleanup}:${ref.id}`;
function applicable(job: Job, action: Action) {
  if (job.type === "CLEANUP") {
    return action === "resume"
      ? job.status === "PAUSED"
      : action === "cancel" &&
          ["QUEUED", "RUNNING", "PAUSED"].includes(job.status);
  }
  if (action === "pause") return active.includes(job.status);
  if (action === "cancel") return [...active, "PAUSED"].includes(job.status);
  return (
    ["PAUSED", "RETRY_WAIT"].includes(job.status) &&
    !(
      job.type === "UPDATE" &&
      job.failed > 0 &&
      job.pending === 0 &&
      job.inFlight === 0
    )
  );
}
export function DownloadJobControls({
  selected,
  rows,
  busy,
  onBusy,
  deletion,
}: {
  selected: Ref[];
  rows?: Job[];
  busy: boolean;
  onBusy: (busy: boolean) => void;
  deletion: ReactNode;
}) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const cache = useQueryClient();
  const [known, setKnown] = useState<Map<string, Job>>(new Map());
  const [cancel, setCancel] = useState<Ref[] | null>(null);
  const [report, setReport] = useState<{
    done: number;
    skipped: number;
    errors: string[];
  } | null>(null);
  useEffect(() => {
    setKnown((current) => {
      const next = new Map<string, Job>();
      for (const ref of selected) {
        const previous = current.get(key(ref));
        if (previous) next.set(key(ref), previous);
      }
      for (const job of rows ?? [])
        next.set(key({ id: job.jobId, cleanup: job.type === "CLEANUP" }), job);
      return next;
    });
  }, [rows, selected]);
  function allowed(ref: Ref, job: Job, action: Action) {
    return (
      (ref.cleanup
        ? can("TORRENT_SYNC") && can("DOWNLOADS_DELETE")
        : can("TORRENT_JOBS_MANAGE")) && applicable(job, action)
    );
  }
  const targets = (action: Action) =>
    selected.filter((ref) => {
      const job =
        rows?.find(
          (row) =>
            row.jobId === ref.id && (row.type === "CLEANUP") === ref.cleanup,
        ) ?? known.get(key(ref));
      return job && allowed(ref, job, action);
    });
  const mutation = useMutation({
    retry: false,
    mutationFn: async ({ action, refs }: { action: Action; refs: Ref[] }) => {
      const outcome = { done: 0, skipped: 0, errors: [] as string[] };
      // Sequential requests keep existing executor locks and cancellation propagation.
      for (const ref of refs) {
        const url = ref.cleanup
          ? `/revision-cleanups/${encodeURIComponent(ref.id)}`
          : `/torrent/jobs/${encodeURIComponent(ref.id)}`;
        try {
          const fresh = ref.cleanup
            ? await get<Job & { state: string }>(url).then((job) => ({
                ...job,
                type: "CLEANUP" as const,
                status: job.state,
              }))
            : await get<Job>(url);
          setKnown((current) => new Map(current).set(key(ref), fresh));
          if (!allowed(ref, fresh, action)) {
            outcome.skipped++;
            continue;
          }
          const response = await post<Job & { state?: string }>(
            `${url}/${ref.cleanup && action === "resume" ? "continue" : action}`,
          );
          setKnown((current) =>
            new Map(current).set(
              key(ref),
              ref.cleanup
                ? { ...response, type: "CLEANUP", status: response.state! }
                : response,
            ),
          );
          outcome.done++;
        } catch (error) {
          outcome.errors.push(
            `${ref.id}: ${error instanceof Error ? error.message : t("downloads.refreshError")}`,
          );
        }
      }
      return outcome;
    },
    onMutate: () => {
      onBusy(true);
      setReport(null);
    },
    onSuccess: setReport,
    onSettled: () => {
      onBusy(false);
      for (const prefix of [
        "jobs",
        "job",
        "revision-cleanup",
        "calibre-jobs",
        "calibre-job",
        "home-summary",
        "home-attention",
        "cleanup-failure-history",
      ])
        void cache.invalidateQueries({ queryKey: [prefix] });
    },
  });
  return (
    <>
      {(["resume", "pause", "cancel"] as const).map((action) => {
        const refs = targets(action);
        if (!refs.length) return null;
        const Icon =
          action === "resume" ? Play : action === "pause" ? Pause : CircleStop;
        return (
          <Button
            key={action}
            disabled={busy || mutation.isPending}
            loading={
              mutation.isPending && mutation.variables?.action === action
            }
            variant="light"
            color={action === "cancel" ? "red" : undefined}
            leftSection={<Icon size={16} />}
            onClick={() =>
              action === "cancel"
                ? setCancel(refs)
                : mutation.mutate({ action, refs })
            }
          >
            {t(
              action === "resume"
                ? "revisionCleanup.continue"
                : action === "pause"
                  ? "downloads.pause"
                  : "downloads.cancelJob",
            )}{" "}
            ({refs.length})
          </Button>
        );
      })}
      {deletion}
      {report && (
        <Alert color={report.errors.length ? "orange" : "green"}>
          <span>
            {t("jobSelection.result", {
              done: report.done,
              skipped: report.skipped,
              failed: report.errors.length,
            })}
          </span>
          {report.errors.length > 0 && (
            <ul>
              {report.errors.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          )}
        </Alert>
      )}
      <AppModal
        opened={cancel !== null}
        onClose={() => setCancel(null)}
        title={t("downloads.cancelJob")}
        icon={CircleStop}
        width="compact"
      >
        <p>{t("jobSelection.cancel", { count: cancel?.length ?? 0 })}</p>
        <p>{t("downloads.cancelConfirm")}</p>
        <ModalActions>
          <Button variant="default" onClick={() => setCancel(null)}>
            {t("common.cancel")}
          </Button>
          <Button
            color="red"
            disabled={busy}
            onClick={() => {
              if (cancel) mutation.mutate({ action: "cancel", refs: cancel });
              setCancel(null);
            }}
          >
            {t("downloads.cancelJob")}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
