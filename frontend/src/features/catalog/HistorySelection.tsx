import { useState } from "react";
import { Alert, Button, Checkbox, Menu } from "@mantine/core";
import { ChevronDown, RefreshCw, Trash2 } from "lucide-react";
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
  const [removal, setRemoval] = useState<boolean | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [outcomes, setOutcomes] = useState<Outcome[] | null>(null);
  const action = useMutation({
    meta: { backendEvents: true },
    mutationFn: (operation: "refresh" | "remove") =>
      post<{ items: Outcome[] }>(
        `/torrent/downloads/${operation === "refresh" ? "refresh-selected" : "remove-selected"}`,
        {
          eplId,
          ids: selected,
          ...(operation === "remove"
            ? { deleteFiles: removal, confirmFiles: confirmed }
            : {}),
        },
      ),
    retry: false,
    onSuccess: (data) => {
      setRemoval(null);
      setConfirmed(false);
      setOutcomes(data.items);
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
  function openRemoval(files: boolean) {
    action.reset();
    setConfirmed(false);
    setRemoval(files);
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
            {can("TORRENT_CLEANUP") && (
              <Menu>
                <Menu.Target>
                  <Button
                    color="red"
                    variant="light"
                    disabled={action.isPending}
                    leftSection={<Trash2 size={16} />}
                    rightSection={<ChevronDown size={16} />}
                  >
                    {t("historyActions.remove")}
                  </Button>
                </Menu.Target>
                <Menu.Dropdown>
                  <Menu.Item onClick={() => openRemoval(false)}>
                    {t("historyActions.torrentOnly")}
                  </Menu.Item>
                  {can("TORRENT_FILES_DELETE") && (
                    <Menu.Item color="red" onClick={() => openRemoval(true)}>
                      {t("historyActions.torrentAndFiles")}
                    </Menu.Item>
                  )}
                </Menu.Dropdown>
              </Menu>
            )}
          </div>
        </div>
      )}
      {action.isError && removal === null && (
        <Alert color="red" role="alert">
          {(action.error instanceof ActionError && action.error.details) ||
            t("historyActions.failed")}
        </Alert>
      )}
      <AppModal
        icon={Trash2}
        opened={removal !== null}
        onClose={() => {
          if (!action.isPending) setRemoval(null);
        }}
        title={t("historyActions.remove")}
        centered
      >
        <p>
          {t(
            removal
              ? "historyActions.confirmFiles"
              : "historyActions.confirmTorrent",
            { count: selected.length },
          )}
        </p>
        <ul className="history-action-records">
          {rows
            .filter((r) => selected.includes(r.id))
            .map((r) => (
              <li key={r.id}>
                {t("downloads.revision")} {number(r.revision)}{" "}
                <small className="hash-text">{r.hash}</small>
              </li>
            ))}
        </ul>
        {removal && (
          <Checkbox
            checked={confirmed}
            onChange={(e) => setConfirmed(e.currentTarget.checked)}
            label={t("historyActions.irreversible")}
          />
        )}
        {action.isError && (
          <Alert color="red" role="alert" mt="md">
            {(action.error instanceof ActionError && action.error.details) ||
              t("historyActions.failed")}
          </Alert>
        )}
        <ModalActions>
          <Button
            variant="default"
            disabled={action.isPending}
            onClick={() => setRemoval(null)}
          >
            {t("import.cancel")}
          </Button>
          <Button
            color="red"
            loading={action.isPending}
            disabled={removal === true && !confirmed}
            onClick={() => action.mutate("remove")}
          >
            {t("historyActions.remove")}
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
                {t("downloads.revision")}{" "}
                {number(rows.find((r) => r.id === item.id)?.revision ?? null)}
              </span>
              {" · "}
              <strong>
                {item.cleanupState
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
