import { useState, type ReactNode } from "react";
import { Alert, Button, Checkbox, Select } from "@mantine/core";
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
  revision?: number;
  eplId?: number;
};
type Row = Pick<Download, "id" | "hash" | "revision"> & { eplId?: number };
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
export function DownloadActions({
  eplId,
  selected,
  rows,
  onDone,
  extraActions,
  children,
}: {
  eplId?: number;
  selected: string[];
  rows: Row[];
  onDone: (ids: string[]) => void;
  extraActions?: ReactNode;
  children?: (actions: RowActions) => ReactNode;
}) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const { status, revision } = useLocale();
  const cache = useQueryClient();
  const [removal, setRemoval] = useState(false);
  const [targets, setTargets] = useState<Targets>({ ids: [], rows: [] });
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
    mutationFn: (request: Operation) =>
      post<{ items: Outcome[] }>(
        `/torrent/downloads/${request.operation === "refresh" ? "refresh-selected" : "remove-records"}`,
        {
          eplId: request.eplId,
          ids: request.ids,
          ...(request.operation === "remove"
            ? {
                clientAction: request.clientAction,
                deleteHistory: request.deleteHistory,
                confirm: true,
                confirmFiles: request.confirmFiles,
              }
            : {}),
        },
      ),
    retry: false,
    onSuccess: (data, request) => {
      setRemoval(false);
      setConfirmed(false);
      setOutcomes(
        data.items.map((item) => ({
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
      {selected.length > 0 && (
        <div className="catalog-selection-bar has-selection">
          <div className="catalog-selection-info">
            <span className="catalog-selection-count" aria-live="polite">
              {t("historyActions.selected", { count: selected.length })}
            </span>
          </div>
          <div className="catalog-selection-actions">
            {extraActions}
            {can("TORRENT_SYNC") && (
              <Button
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
          </div>
        </div>
      )}
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
        opened={removal}
        onClose={() => {
          if (!action.isPending) setRemoval(false);
        }}
        title={t("historyActions.delete")}
        centered
      >
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
              <p>
                {t("historyActions.confirmHistory", {
                  count: targets.ids.length,
                })}
              </p>
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
            label={t("send.previousAction")}
            value={clientAction}
            allowDeselect={false}
            disabled={action.isPending}
            data={[
              { value: "keep", label: t("send.keepPrevious") },
              ...(can("TORRENT_CLEANUP")
                ? [{ value: "torrent", label: t("historyActions.torrentOnly") }]
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
          {clientAction === "files" && (
            <Checkbox
              mt="md"
              checked={confirmed}
              disabled={action.isPending}
              onChange={(e) => setConfirmed(e.currentTarget.checked)}
              label={t("historyActions.irreversible")}
            />
          )}
        </fieldset>
        {deleteHistory && clientAction !== "keep" && (
          <p className="muted">{t("historyActions.combinedHelp")}</p>
        )}
        <ul className="history-action-records">
          {targets.rows.map((row) => (
            <li key={row.id}>
              {(row.eplId ?? targets.eplId) != null && (
                <>
                  {t("filters.eplId")} {row.eplId ?? targets.eplId} ·{" "}
                </>
              )}
              {t("downloads.revision")} {revision(row.revision)}{" "}
              <small className="hash-text">{row.hash}</small>
            </li>
          ))}
        </ul>
        {action.isError && (
          <Alert color="red" role="alert">
            {action.error.message || t("historyActions.failed")}
          </Alert>
        )}
        <ModalActions>
          <Button
            variant="default"
            disabled={action.isPending}
            onClick={() => setRemoval(false)}
          >
            {t("import.cancel")}
          </Button>
          <Button
            color="red"
            loading={action.isPending}
            disabled={
              (!deleteHistory && clientAction === "keep") ||
              (deleteHistory && !preview.isSuccess) ||
              (clientAction === "files" && !confirmed)
            }
            onClick={() =>
              action.mutate({
                ...targets,
                operation: "remove",
                clientAction,
                deleteHistory,
                confirmFiles: confirmed,
              })
            }
          >
            {t("historyActions.delete")}
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
