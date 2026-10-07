import { useState } from "react";
import { Alert, Button, Checkbox, Select } from "@mantine/core";
import { RefreshCw, Trash2 } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/Auth";
import { useLocale } from "../../locales/useLocale";
import { AppModal, ModalActions } from "../../components/AppModal";
import { post, ActionError } from "../downloads/shared";
import type { Download } from "../downloads/DownloadState";

type Outcome = {
  id: string;
  status: string;
  cleanupState: string | null;
  message: string | null;
  historyDeleted?: boolean;
  revision?: number;
};
export function HistorySelection({
  eplId,
  selected,
  rows,
  onDone,
}: {
  eplId: number;
  selected: string[];
  rows: Pick<Download, "id" | "hash" | "revision">[];
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const { status, number } = useLocale();
  const cache = useQueryClient();
  const [removal, setRemoval] = useState(false);
  const [clientAction, setClientAction] = useState("keep");
  const [deleteHistory, setDeleteHistory] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [outcomes, setOutcomes] = useState<Outcome[] | null>(null);
  const preview = useMutation({
    mutationFn: () =>
      post<{ pendingCleanup: number }>(
        "/torrent/downloads/delete-history?preview=true",
        { eplId, ids: selected },
      ),
    retry: false,
  });
  const action = useMutation({
    meta: { backendEvents: true },
    mutationFn: (operation: "refresh" | "remove") =>
      post<{ items: Outcome[] }>(
        `/torrent/downloads/${operation === "refresh" ? "refresh-selected" : "remove-records"}`,
        {
          eplId,
          ids: selected,
          ...(operation === "remove"
            ? {
                clientAction,
                deleteHistory,
                confirm: true,
                confirmFiles: confirmed,
              }
            : {}),
        },
      ),
    retry: false,
    onSuccess: (data) => {
      setRemoval(false);
      setConfirmed(false);
      setOutcomes(
        data.items.map((item) => ({
          ...item,
          revision: rows.find((row) => row.id === item.id)?.revision,
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
      onDone();
    },
  });
  function openRemoval() {
    action.reset();
    preview.reset();
    setConfirmed(false);
    setDeleteHistory(false);
    setClientAction("keep");
    setRemoval(true);
    if (can("DOWNLOADS_DELETE")) preview.mutate();
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
            {can("TORRENT_SYNC") && (
              <Button
                leftSection={<RefreshCw size={16} />}
                loading={action.isPending}
                onClick={() => action.mutate("refresh")}
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
                onClick={openRemoval}
              >
                {t("historyActions.delete")}
              </Button>
            )}
          </div>
        </div>
      )}
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
                {t("historyActions.confirmHistory", { count: selected.length })}
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
              onChange={(e) => setConfirmed(e.currentTarget.checked)}
              label={t("historyActions.irreversible")}
            />
          )}
        </fieldset>
        {deleteHistory && clientAction !== "keep" && (
          <p className="muted">{t("historyActions.combinedHelp")}</p>
        )}
        <ul className="history-action-records">
          {rows
            .filter((row) => selected.includes(row.id))
            .map((row) => (
              <li key={row.id}>
                {t("downloads.revision")} {number(row.revision)}{" "}
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
            onClick={() => action.mutate("remove")}
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
                {t("downloads.revision")} {number(item.revision ?? null)}
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
