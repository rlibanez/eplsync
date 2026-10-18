import { useRangeSelection } from "../../components/useRangeSelection";
import { useEffect, useState } from "react";
import { Alert, Button, Checkbox } from "@mantine/core";
import { Trash2 } from "lucide-react";
import { Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { useAuth } from "../auth/Auth";
import { post } from "../downloads/shared";

type Ref = { id: string; cleanup: boolean };
type Item = Ref & {
  allowed: boolean;
  deleted: boolean;
  reason: string | null;
  relatedSource: string | null;
  relatedId: string | null;
  relatedCleanup: boolean;
};
type Result = { items: Item[] };
const key = (row: Ref) => `${row.cleanup}:${row.id}`;

export function useWorkDeletion(
  source: "downloads" | "calibre",
  rows: Ref[],
  context: string,
  pageScope = "",
) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const cache = useQueryClient();
  const manage = can(
    source === "downloads" ? "TORRENT_JOBS_MANAGE" : "CALIBRE_JOBS_MANAGE",
  );
  const clean =
    source === "downloads"
      ? can("TORRENT_SYNC") && can("DOWNLOADS_DELETE")
      : can("CALIBRE_READ") && can("CALIBRE_DELETE");
  const visible = manage || clean;
  const available = rows.filter((row) => (row.cleanup ? clean : manage));
  const [selected, setSelected] = useState<Ref[]>([]);
  const [opened, setOpened] = useState(false);
  const [report, setReport] = useState<Result | null>(null);
  const [finished, setFinished] = useState(false);
  const mutation = useMutation({
    mutationFn: ({ preview, jobs }: { preview: boolean; jobs: Ref[] }) =>
      post<Result>(
        `/settings/work/${source}/deletions${preview ? "/preview" : ""}`,
        { jobs },
      ),
    retry: false,
    onSuccess: (response, request) => {
      setReport(response);
      if (!request.preview) {
        setFinished(true);
        setSelected((current) =>
          current.filter(
            (ref) =>
              !response.items.some(
                (item) => item.deleted && key(item) === key(ref),
              ),
          ),
        );
        for (const prefix of [
          "jobs",
          "calibre-jobs",
          "calibre-job",
          "job",
          "job-retries",
          "revision-cleanup",
        ])
          void cache.invalidateQueries({ queryKey: [prefix] });
      }
    },
  });
  useEffect(() => {
    setSelected([]);
    setOpened(false);
    setReport(null);
  }, [context]);
  const busy = mutation.isPending;
  const selectedKey = new Set(selected.map(key));
  const selectRange = useRangeSelection(
    available.map(key),
    (ids, checked) => {
      const keys = new Set(ids);
      setSelected((current) =>
        checked
          ? [
              ...current.filter((row) => !keys.has(key(row))),
              ...available.filter((row) => keys.has(key(row))),
            ]
          : current.filter((row) => !keys.has(key(row))),
      );
    },
    `${context}:${pageScope}`,
  );
  const open = () => {
    setOpened(true);
    setFinished(false);
    setReport(null);
    mutation.reset();
    mutation.mutate({ preview: true, jobs: selected });
  };
  const permitted = report?.items.filter((item) => item.allowed) ?? [];
  return {
    visible,
    count: selected.length,
    busy,
    clear: () => setSelected([]),
    heading: visible && (
      <th>
        <Checkbox
          aria-label={t("selection.page")}
          disabled={busy || !available.length}
          checked={
            !!available.length &&
            available.every((row) => selectedKey.has(key(row)))
          }
          indeterminate={
            available.some((row) => selectedKey.has(key(row))) &&
            !available.every((row) => selectedKey.has(key(row)))
          }
          onChange={(event) => {
            const checked = event.currentTarget.checked;
            setSelected((current) =>
              checked
                ? [
                    ...current.filter(
                      (ref) => !available.some((row) => key(row) === key(ref)),
                    ),
                    ...available,
                  ]
                : current.filter(
                    (ref) => !available.some((row) => key(row) === key(ref)),
                  ),
            );
          }}
        />
      </th>
    ),
    cell: (row: Ref) =>
      visible && (
        <td>
          <Checkbox
            aria-label={`${t("downloads.job")}: ${row.id}`}
            disabled={busy || !(row.cleanup ? clean : manage)}
            checked={selectedKey.has(key(row))}
            onMouseDown={selectRange.preventTextSelection}
            onChange={(event) => selectRange(key(row), event)}
          />
        </td>
      ),
    action: (
      <Button
        color="red"
        variant="light"
        leftSection={<Trash2 size={16} />}
        disabled={busy}
        onClick={open}
      >
        {t("workDeletion.delete")}
      </Button>
    ),
    content: (
      <AppModal
        opened={opened}
        onClose={() => {
          if (!busy) setOpened(false);
        }}
        title={t("workDeletion.delete")}
        icon={Trash2}
      >
        <p>{t("workDeletion.help")}</p>
        {mutation.isError && (
          <Alert color="red">{mutation.error.message}</Alert>
        )}
        {report && (
          <>
            <p>
              {t(finished ? "workDeletion.deleted" : "workDeletion.available", {
                count: report.items.filter((item) =>
                  finished ? item.deleted : item.allowed,
                ).length,
              })}
            </p>
            <ul className="work-deletion-results">
              {report.items.map((item) => (
                <li key={key(item)}>
                  <code>{item.id}</code>:{" "}
                  {item.deleted
                    ? t("workDeletion.removed")
                    : item.allowed
                      ? t("workDeletion.allowed")
                      : item.reason}
                  {item.relatedId && item.relatedSource && (
                    <>
                      {" "}
                      ·{" "}
                      <Link
                        to={`/${item.relatedSource}/jobs/${item.relatedCleanup ? "cleanup/" : ""}${item.relatedId}`}
                      >
                        {t("workDeletion.related")}
                      </Link>
                    </>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}
        <ModalActions>
          <Button
            variant="default"
            disabled={busy}
            onClick={() => setOpened(false)}
          >
            {t(finished ? "common.close" : "common.cancel")}
          </Button>
          {!finished && (
            <Button
              color="red"
              loading={busy}
              disabled={!permitted.length || busy}
              onClick={() =>
                mutation.mutate({
                  preview: false,
                  jobs: permitted.map(({ id, cleanup }) => ({ id, cleanup })),
                })
              }
            >
              {t("workDeletion.delete")}
            </Button>
          )}
        </ModalActions>
      </AppModal>
    ),
  };
}
