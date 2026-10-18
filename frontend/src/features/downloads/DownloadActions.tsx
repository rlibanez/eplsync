import { PreviewHeading, usePreviewSort } from "../../components/PreviewSort";
import { RowNumberCell, RowNumberHeading } from "../../components/RowNumber";
import { Link, useNavigate } from "react-router-dom";
import { SelectionToolbar } from "../../components/SelectionToolbar";
import { useRef, useState, type ReactNode } from "react";
import { Alert, Button, Checkbox, Group, Select, Stepper } from "@mantine/core";
import { RefreshCw, Trash2 } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/Auth";
import { useLocale } from "../../locales/useLocale";
import { AppModal, ModalActions } from "../../components/AppModal";
import { post, ActionError } from "./shared";
import type { Download } from "./DownloadState";

type Outcome = {
  id: string;
  status: string;
  cleanupState: string | null;
  message: string | null;
  historyDeleted?: boolean;
  blockingCalibreJobId?: string | null;
  revision?: number;
  eplId?: number;
};
type Row = Pick<Download, "id" | "hash" | "revision"> & {
  eplId?: number;
  title?: string | null;
};
type Targets = { eplId?: number; ids: string[]; rows: Row[] };
type Operation = Targets & {
  operation: "refresh" | "remove";
  clientAction?: string;
  deleteHistory?: boolean;
  confirmFiles?: boolean;
};
type RowActions = {
  pending: boolean;
  refreshingIds: string[];
  refreshRow: (row: Row) => void;
  removeRow: (row: Row) => void;
};
// getRandomValues also works on the HTTP LAN installations supported by EPL Sync.
function removalId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
export function DownloadActions({
  eplId,
  selected,
  rows,
  onDone,
  extraActions,
  toolbar,
  children,
}: {
  eplId?: number;
  selected: string[];
  rows: Row[];
  onDone: (ids: string[]) => void;
  extraActions?: ReactNode;
  toolbar: { selectAll?: ReactNode; controls: ReactNode; onClear: () => void };
  children?: (actions: RowActions) => ReactNode;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const removalRequest = useRef<{ signature: string; id: string } | null>(null);
  const { can } = useAuth();
  const { status, revision } = useLocale();
  const cache = useQueryClient();
  const [removal, setRemoval] = useState(false);
  const [targets, setTargets] = useState<Targets>({ ids: [], rows: [] });
  const [removalPage, setRemovalPage] = useState(0);
  const [removalStep, setRemovalStep] = useState(0);
  const [included, setIncluded] = useState<string[]>([]);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [detailsError, setDetailsError] = useState<string | null>(null);
  const previewGeneration = useRef(0);
  const removalSorting = usePreviewSort(targets.rows, () => setRemovalPage(0));
  const removalRows = removalSorting.sorted((row, field) =>
    field === "eplId"
      ? (row.eplId ?? targets.eplId)
      : row[field as "revision" | "hash" | "title"],
  );
  const [clientAction, setClientAction] = useState("keep");
  const [deleteHistory, setDeleteHistory] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [outcomes, setOutcomes] = useState<Outcome[] | null>(null);
  const preview = useMutation({
    mutationFn: (target: Targets) =>
      post<{ pendingCleanup: number }>(
        "/torrent/downloads/delete-history?preview=true",
        { eplId: target.eplId, ids: target.ids },
      ),
    retry: false,
  });
  const action = useMutation({
    meta: { backendEvents: true },
    mutationFn: (request: Operation) => {
      if (request.operation === "refresh")
        return post<{ items: Outcome[]; id?: string }>(
          "/torrent/downloads/refresh-selected",
          { eplId: request.eplId, ids: request.ids },
        );
      const removal = {
        eplId: request.eplId,
        ids: request.ids,
        clientAction: request.clientAction,
        deleteHistory: request.deleteHistory,
        confirm: true,
        confirmFiles: request.confirmFiles,
      };
      if (!can("TORRENT_SYNC"))
        return post<{ items: Outcome[]; id?: string }>(
          "/torrent/downloads/remove-records",
          removal,
        );
      const signature = JSON.stringify(removal);
      if (removalRequest.current?.signature !== signature)
        removalRequest.current = { signature, id: removalId() };
      return post<{ id?: string; items?: Outcome[] }>(
        "/revision-cleanups/removals",
        { requestId: removalRequest.current.id, removal },
      );
    },
    retry: false,
    onSuccess: (data, request) => {
      setRemoval(false);
      setConfirmed(false);
      if (data.id) {
        for (const key of ["jobs", "revision-cleanups", "downloads"])
          void cache.invalidateQueries({ queryKey: [key] });
        onDone(request.ids);
        navigate(`/downloads/jobs/cleanup/${data.id}`);
        return;
      }
      setOutcomes(
        (data.items ?? []).map((item) => ({
          ...item,
          revision: request.rows.find((row) => row.id === item.id)?.revision,
          eplId:
            request.rows.find((row) => row.id === item.id)?.eplId ??
            request.eplId,
        })),
      );
      for (const key of [
        "book-history",
        "downloads",
        "download-summary",
        "catalog",
        "book",
        "jobs",
        "job",
        "revision-updates",
      ])
        void cache.invalidateQueries({ queryKey: [key] });
      onDone(request.ids);
    },
  });
  function selection(): Targets {
    return {
      eplId,
      ids: [...selected],
      rows: rows.filter((row) => selected.includes(row.id)),
    };
  }
  function refresh(target: Targets) {
    setOutcomes(null);
    action.mutate({ ...target, operation: "refresh" });
  }
  function openRemoval(target: Targets) {
    const generation = ++previewGeneration.current;
    removalRequest.current = null;
    setRemovalPage(0);
    setRemovalStep(0);
    setIncluded(target.ids);
    setDetailsError(null);
    const missingDetails = target.rows.length !== target.ids.length;
    const targetIds = new Set(target.ids);
    setDetailsLoading(missingDetails);
    if (missingDetails)
      void post<Row[]>("/torrent/downloads/removal-preview", {
        eplId: target.eplId,
        ids: target.ids,
      })
        .then((allRows) => {
          if (previewGeneration.current !== generation) return;
          if (
            allRows.length !== target.ids.length ||
            new Set(allRows.map((row) => row.id)).size !== target.ids.length ||
            allRows.some((row) => !targetIds.has(row.id))
          )
            throw new Error(t("historyActions.failed"));
          const byId = new Map(allRows.map((row) => [row.id, row]));
          setTargets({
            ...target,
            rows: target.ids.map((id) => byId.get(id)!),
          });
        })
        .catch((error) => {
          if (previewGeneration.current === generation)
            setDetailsError(error.message);
        })
        .finally(() => {
          if (previewGeneration.current === generation)
            setDetailsLoading(false);
        });
    setTargets(target);
    setOutcomes(null);
    action.reset();
    preview.reset();
    setConfirmed(false);
    setDeleteHistory(false);
    setClientAction("keep");
    setRemoval(true);
    if (can("DOWNLOADS_DELETE")) preview.mutate(target);
  }
  return (
    <>
      <SelectionToolbar
        count={selected.length}
        onClear={toolbar.onClear}
        selectAll={toolbar.selectAll}
        controls={toolbar.controls}
        disabled={action.isPending}
        actions={
          <>
            {extraActions}
            {can("TORRENT_SYNC") && (
              <Button
                variant="light"
                leftSection={<RefreshCw size={16} />}
                loading={action.isPending}
                onClick={() => refresh(selection())}
              >
                {t("historyActions.refresh")}
              </Button>
            )}
            {(can("DOWNLOADS_DELETE") || can("TORRENT_CLEANUP")) && (
              <Button
                color="red"
                variant="light"
                disabled={action.isPending}
                leftSection={<Trash2 size={16} />}
                onClick={() => openRemoval(selection())}
              >
                {t("historyActions.delete")}
              </Button>
            )}
          </>
        }
      />
      {children?.({
        pending: action.isPending,
        refreshingIds:
          action.isPending && action.variables.operation === "refresh"
            ? action.variables.ids
            : [],
        refreshRow: (row) => refresh({ eplId, ids: [row.id], rows: [row] }),
        removeRow: (row) => openRemoval({ eplId, ids: [row.id], rows: [row] }),
      })}
      {action.isError && !removal && (
        <Alert color="red" role="alert">
          {(action.error instanceof ActionError && action.error.details) ||
            t("historyActions.failed")}
        </Alert>
      )}
      <AppModal
        icon={Trash2}
        width="wide"
        opened={removal}
        onClose={() => {
          if (!action.isPending) setRemoval(false);
        }}
        title={t("historyActions.delete")}
        centered
      >
        <Stepper active={removalStep} size="xs" allowNextStepsSelect={false}>
          <Stepper.Step label={t("revisionCleanup.preview")} />
          <Stepper.Step label={t("revisionCleanup.confirm")} />
        </Stepper>
        {removalStep === 0 ? (
          <>
            <fieldset className="send-options-group">
              <legend>{t("historyActions.inApp")}</legend>
              <Checkbox
                label={t("historyActions.historyRecord")}
                checked={deleteHistory}
                disabled={!can("DOWNLOADS_DELETE") || action.isPending}
                onChange={(e) => setDeleteHistory(e.currentTarget.checked)}
              />
              {deleteHistory && (
                <>
                  <p className="muted">{t("historyActions.rediscovery")}</p>
                  {preview.data && preview.data.pendingCleanup > 0 && (
                    <Alert color="yellow">
                      {t("historyActions.pendingCleanup")}
                    </Alert>
                  )}
                  {preview.isError && (
                    <Alert color="red">{preview.error.message}</Alert>
                  )}
                </>
              )}
            </fieldset>
            <fieldset className="send-options-group">
              <legend>{t("historyActions.inClient")}</legend>
              <Select
                aria-label={t("historyActions.inClient")}
                value={clientAction}
                allowDeselect={false}
                disabled={action.isPending}
                data={[
                  { value: "keep", label: t("send.keepPrevious") },
                  ...(can("TORRENT_CLEANUP")
                    ? [
                        {
                          value: "torrent",
                          label: t("historyActions.torrentOnly"),
                        },
                      ]
                    : []),
                  ...(can("TORRENT_CLEANUP") && can("TORRENT_FILES_DELETE")
                    ? [
                        {
                          value: "files",
                          label: t("historyActions.torrentAndFiles"),
                        },
                      ]
                    : []),
                ]}
                onChange={(value) => {
                  setClientAction(value ?? "keep");
                  setConfirmed(false);
                }}
              />
            </fieldset>
            {deleteHistory && clientAction !== "keep" && (
              <p className="muted">{t("historyActions.combinedHelp")}</p>
            )}
            <fieldset className="send-options-group cleanup-preview">
              <legend>{t("revisionCleanup.previewBooks")}</legend>
              <Group gap="xs">
                <Button
                  variant="subtle"
                  disabled={action.isPending || detailsLoading}
                  onClick={() => setIncluded(targets.ids)}
                >
                  {t("revisionCleanup.selectAll")}
                </Button>
                <Button
                  variant="subtle"
                  disabled={action.isPending || detailsLoading}
                  onClick={() => setIncluded([])}
                >
                  {t("revisionCleanup.clear", { count: included.length })}
                </Button>
              </Group>
              {detailsLoading && <p role="status">{t("feedback.loading")}</p>}
              {detailsError && <Alert color="red">{detailsError}</Alert>}
              <div className="table-scroll revision-cleanup-table download-removal-preview">
                <table>
                  <thead>
                    <tr>
                      <th aria-label={t("revisionCleanup.selection")} />
                      <RowNumberHeading />
                      <PreviewHeading field="title" {...removalSorting}>
                        {t("downloads.book")}
                      </PreviewHeading>
                      <PreviewHeading field="eplId" {...removalSorting}>
                        EPL ID
                      </PreviewHeading>
                      <PreviewHeading field="revision" {...removalSorting}>
                        {t("downloads.revision")}
                      </PreviewHeading>
                      <PreviewHeading field="hash" {...removalSorting}>
                        Hash
                      </PreviewHeading>
                    </tr>
                  </thead>
                  <tbody>
                    {removalRows
                      .slice(removalPage * 20, (removalPage + 1) * 20)
                      .map((row, index) => (
                        <tr key={row.id}>
                          <td>
                            <Checkbox
                              aria-label={`${t("filters.eplId")} ${row.eplId ?? targets.eplId} · ${row.hash}`}
                              checked={included.includes(row.id)}
                              disabled={action.isPending || detailsLoading}
                              onChange={(event) => {
                                const checked = event.currentTarget.checked;
                                setIncluded((old) =>
                                  checked
                                    ? [...old, row.id]
                                    : old.filter((id) => id !== row.id),
                                );
                              }}
                            />
                          </td>
                          <RowNumberCell value={removalPage * 20 + index + 1} />
                          <td>{row.title || "—"}</td>
                          <td>{row.eplId ?? targets.eplId ?? "—"}</td>
                          <td>{revision(row.revision)}</td>
                          <td className="hash-text">{row.hash}</td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
              {targets.rows.length > 20 && (
                <nav
                  className="pagination cleanup-preview-pagination"
                  aria-label={t("revisionCleanup.previewPages")}
                >
                  <Button
                    variant="subtle"
                    disabled={!removalPage}
                    onClick={() => setRemovalPage((page) => page - 1)}
                  >
                    {t("catalog.previous")}
                  </Button>
                  <span>
                    {removalPage + 1} / {Math.ceil(targets.rows.length / 20)}
                  </span>
                  <Button
                    variant="subtle"
                    disabled={(removalPage + 1) * 20 >= targets.rows.length}
                    onClick={() => setRemovalPage((page) => page + 1)}
                  >
                    {t("catalog.next")}
                  </Button>
                </nav>
              )}
            </fieldset>
          </>
        ) : (
          <>
            <p>
              <strong>
                {t(
                  `historyActions.confirmation.${clientAction === "keep" ? "records" : clientAction === "torrent" ? (deleteHistory ? "torrentsAndRecords" : "torrents") : deleteHistory ? "filesAndRecords" : "files"}`,
                  { count: included.length },
                )}
              </strong>
            </p>
            {deleteHistory && clientAction !== "keep" && (
              <p className="muted">{t("historyActions.combinedHelp")}</p>
            )}
            <Checkbox
              checked={confirmed}
              disabled={action.isPending}
              onChange={(event) => setConfirmed(event.currentTarget.checked)}
              label={t("common.confirmDeletion")}
            />
          </>
        )}
        {action.isError && (
          <Alert color="red" role="alert">
            {action.error.message || t("historyActions.failed")}
          </Alert>
        )}
        <ModalActions
          summary={
            removalStep === 0
              ? t("historyActions.selected", { count: included.length })
              : undefined
          }
        >
          <Button
            variant="default"
            disabled={action.isPending}
            onClick={() => {
              if (removalStep) {
                setRemovalStep(0);
                setConfirmed(false);
              } else setRemoval(false);
            }}
          >
            {t(removalStep ? "revisionCleanup.back" : "common.cancel")}
          </Button>
          <Button
            color={removalStep ? "red" : undefined}
            loading={action.isPending}
            disabled={
              !included.length ||
              detailsLoading ||
              !!detailsError ||
              (!deleteHistory && clientAction === "keep") ||
              (deleteHistory && !preview.isSuccess) ||
              (removalStep === 1 && !confirmed)
            }
            onClick={() => {
              if (removalStep === 0) {
                setRemovalStep(1);
                setConfirmed(false);
                return;
              }
              action.mutate({
                ...targets,
                ids: included,
                operation: "remove",
                clientAction,
                deleteHistory,
                confirmFiles: clientAction === "files" && confirmed,
              });
            }}
          >
            {t(removalStep ? "historyActions.delete" : "revisionCleanup.next")}
          </Button>
        </ModalActions>
      </AppModal>
      <AppModal
        icon={RefreshCw}
        opened={outcomes !== null}
        onClose={() => setOutcomes(null)}
        title={t("historyActions.result")}
        centered
      >
        <ul className="history-action-records">
          {outcomes?.map((item) => (
            <li key={item.id}>
              <span>
                {item.eplId != null && (
                  <>
                    {t("filters.eplId")} {item.eplId} ·{" "}
                  </>
                )}
                {t("downloads.revision")} {revision(item.revision ?? null)}
              </span>
              {" · "}
              <strong>
                {item.historyDeleted
                  ? t("historyActions.historyRemoved")
                  : item.cleanupState
                    ? t(`historyActions.states.${item.cleanupState}`)
                    : status(item.status)}
              </strong>
              {item.message && <p className="muted">{item.message}</p>}
              {item.blockingCalibreJobId && (
                <Link
                  to={`/calibre/jobs/${encodeURIComponent(item.blockingCalibreJobId)}`}
                >
                  {t("calibreUpdateSend.calibreJob")}
                </Link>
              )}
            </li>
          ))}
        </ul>
        <ModalActions>
          <Button onClick={() => setOutcomes(null)}>{t("common.close")}</Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
