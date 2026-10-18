import { PreviewHeading, usePreviewSort } from "../../components/PreviewSort";
import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button, Checkbox, Group, Progress } from "@mantine/core";
import { Tags } from "lucide-react";
import { useTranslation } from "react-i18next";
import { RowNumberHeading, RowNumberCell } from "../../components/RowNumber";
import { TableAction } from "../../components/TableAction";
import { AppModal, ModalActions } from "../../components/AppModal";
import { useAuth } from "../auth/Auth";
import { ApiError } from "../../api/catalog";
import { ActionError, post } from "../downloads/shared";

type Row = {
  token: string | null;
  calibreId: number;
  title: string;
  eplId: number | null;
  revision: string | null;
  allowed: boolean;
  message: string;
  checked?: boolean;
  done?: boolean;
};
export function CompleteCalibreIdentifiers({
  selected,
  inline = false,
}: {
  selected: number[];
  inline?: boolean;
}) {
  const auth = useAuth();
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [opened, setOpened] = useState(false),
    [busy, setBusy] = useState(false),
    [rows, setRows] = useState<Row[]>([]),
    [progress, setProgress] = useState(0),
    [page, setPage] = useState(0),
    [attempted, setAttempted] = useState(false);
  const sorting = usePreviewSort(rows, () => setPage(0));
  const displayed = sorting.sorted(
    (row, key) =>
      row[key as "title" | "calibreId" | "eplId" | "revision" | "message"],
  );
  const selectedCount = rows.filter(
    (row) => row.allowed && row.checked && !row.done,
  ).length;
  const pageSize = 20;
  const pages = Math.ceil(rows.length / pageSize);
  type Run = { id: string; abort: AbortController };
  const running = useRef<Run | null>(null);
  function cancelRequest(run: Run) {
    void post("/calibre/identifiers/cancel", { operationId: run.id }).catch(
      () => undefined,
    );
    run.abort.abort();
  }
  useEffect(
    () => () => {
      const run = running.current;
      running.current = null;
      if (run) cancelRequest(run);
    },
    [],
  );
  function close() {
    const run = running.current;
    running.current = null;
    if (run) cancelRequest(run);
    setBusy(false);
    setOpened(false);
  }
  function begin(): Run {
    const run = { id: crypto.randomUUID(), abort: new AbortController() };
    running.current = run;
    setBusy(true);
    setProgress(0);
    return run;
  }
  if (
    !auth.can("CALIBRE_READ") ||
    !auth.can("CALIBRE_FILES_READ") ||
    !auth.can("CALIBRE_SEND") ||
    !auth.can("DOWNLOADS_FILES_READ")
  )
    return null;
  const reason = (error: unknown) =>
    error instanceof ApiError || error instanceof ActionError
      ? error.details || error.message
      : error instanceof Error
        ? error.message
        : t("calibreIdentifiers.failed");
  async function preview() {
    setOpened(true);
    const run = begin();
    setAttempted(false);
    setRows([]);
    setPage(0);
    const result: Row[] = [];
    for (const [index, id] of selected.entries()) {
      try {
        const row = await post<Row>(
          "/calibre/identifiers/preview",
          {
            calibreId: id,
            operationId: run.id,
          },
          run.abort.signal,
        );
        if (running.current !== run) return;
        result.push({ ...row, checked: row.allowed });
      } catch (error) {
        if (running.current !== run) return;
        result.push({
          token: null,
          calibreId: id,
          title: `Calibre ${id}`,
          eplId: null,
          revision: null,
          allowed: false,
          message: reason(error),
        });
      }
      setRows([...result]);
      setProgress((100 * (index + 1)) / selected.length);
    }
    if (running.current === run) {
      running.current = null;
      setBusy(false);
    }
  }
  async function apply() {
    setAttempted(true);
    const chosen = rows.filter(
      (row) => row.allowed && row.checked && !row.done,
    );
    const run = begin();
    for (const [index, row] of chosen.entries()) {
      try {
        const done = await post<Row>(
          "/calibre/identifiers/apply",
          {
            token: row.token,
            operationId: run.id,
          },
          run.abort.signal,
        );
        if (running.current !== run) break;
        setRows((old) =>
          old.map((item) =>
            item.calibreId === row.calibreId
              ? { ...done, done: true, checked: false }
              : item,
          ),
        );
      } catch (error) {
        if (running.current !== run) break;
        setRows((old) =>
          old.map((item) =>
            item.calibreId === row.calibreId
              ? { ...item, message: reason(error) }
              : item,
          ),
        );
      }
      setProgress((100 * (index + 1)) / chosen.length);
    }
    for (const key of [
      "calibre-library",
      "calibre-snapshot",
      "calibre-job-items",
      "calibre-jobs",
      "book",
      "catalog",
      "home-summary",
    ])
      void cache.invalidateQueries({ queryKey: [key] });
    if (running.current === run) {
      running.current = null;
      setBusy(false);
    }
  }
  return (
    <>
      {inline ? (
        <TableAction
          label={t("calibreIdentifiers.open")}
          disabled={!selected.length}
          onClick={() => void preview()}
        >
          <Tags size={16} aria-hidden="true" />
        </TableAction>
      ) : (
        <Button
          variant="light"
          leftSection={<Tags size={16} />}
          disabled={!selected.length}
          onClick={() => void preview()}
        >
          {t("calibreIdentifiers.open")}
        </Button>
      )}
      <AppModal
        icon={Tags}
        opened={opened}
        onClose={close}
        title={t("calibreIdentifiers.open")}
        width="wide"
        closeOnClickOutside
        closeOnEscape
      >
        <p>{t("calibreIdentifiers.help")}</p>
        {busy && (
          <Progress
            value={progress}
            aria-label={t("calibreIdentifiers.progress")}
          />
        )}
        <fieldset className="send-options-group cleanup-preview">
          <legend>{t("calibreIdentifiers.books")}</legend>
          <Group gap="xs">
            <Button
              variant="subtle"
              disabled={busy}
              onClick={() =>
                setRows((old) =>
                  old.map((row) => ({
                    ...row,
                    checked: row.allowed && !row.done,
                  })),
                )
              }
            >
              {t("revisionCleanup.selectAll")}
            </Button>
            <Button
              variant="subtle"
              disabled={busy}
              onClick={() =>
                setRows((old) => old.map((row) => ({ ...row, checked: false })))
              }
            >
              {t("revisionCleanup.clear", { count: selectedCount })}
            </Button>
          </Group>
          <div className="table-scroll revision-cleanup-table identifier-preview-table">
            <table aria-label={t("calibreIdentifiers.books")}>
              <colgroup>
                <col style={{ width: 44 }} />
                <col style={{ width: 44 }} />
                <col style={{ width: 180 }} />
                <col style={{ width: 104 }} />
                <col style={{ width: 80 }} />
                <col style={{ width: 80 }} />
                <col />
              </colgroup>
              <thead>
                <tr>
                  <th aria-label={t("revisionCleanup.selection")} />
                  <RowNumberHeading />
                  <PreviewHeading field="title" {...sorting}>
                    {t("downloads.book")}
                  </PreviewHeading>
                  <PreviewHeading field="calibreId" {...sorting}>
                    {t("calibre.id")}
                  </PreviewHeading>
                  <PreviewHeading field="eplId" {...sorting}>
                    EPL ID
                  </PreviewHeading>
                  <PreviewHeading field="revision" {...sorting}>
                    {t("calibreIdentifiers.revision")}
                  </PreviewHeading>
                  <PreviewHeading field="message" {...sorting}>
                    {t("calibreIdentifiers.result")}
                  </PreviewHeading>
                </tr>
              </thead>
              <tbody>
                {displayed
                  .slice(page * pageSize, (page + 1) * pageSize)
                  .map((row, index) => (
                    <tr key={row.calibreId}>
                      <td>
                        <Checkbox
                          aria-label={`${t("calibreIdentifiers.select")} ${row.calibreId}`}
                          checked={!!row.checked}
                          disabled={busy || !row.allowed || row.done}
                          onChange={(event) => {
                            const checked = event.currentTarget.checked;
                            setRows((old) =>
                              old.map((item) =>
                                item.calibreId === row.calibreId
                                  ? { ...item, checked }
                                  : item,
                              ),
                            );
                          }}
                        />
                      </td>
                      <RowNumberCell value={page * pageSize + index + 1} />
                      <td>{row.title}</td>
                      <td>{row.calibreId}</td>
                      <td>{row.eplId ?? "—"}</td>
                      <td>{row.revision ?? "—"}</td>
                      <td>
                        <span role={row.done ? "status" : undefined}>
                          {row.message}
                        </span>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
          {pages > 1 && (
            <nav
              className="pagination cleanup-preview-pagination"
              aria-label={t("revisionCleanup.previewPages")}
            >
              <Button
                variant="subtle"
                disabled={page === 0}
                onClick={() => setPage((value) => value - 1)}
              >
                {t("catalog.previous")}
              </Button>
              <span>
                {page + 1} / {pages}
              </span>
              <Button
                variant="subtle"
                disabled={page + 1 >= pages}
                onClick={() => setPage((value) => value + 1)}
              >
                {t("catalog.next")}
              </Button>
            </nav>
          )}
        </fieldset>
        <ModalActions
          summary={t("calibreIdentifiers.selected", { count: selectedCount })}
        >
          <Button variant="default" onClick={close}>
            {t(!busy && attempted ? "common.close" : "common.cancel")}
          </Button>
          <Button
            loading={busy}
            disabled={
              !rows.some((row) => row.allowed && row.checked && !row.done)
            }
            onClick={() => void apply()}
          >
            {t("calibreIdentifiers.apply")}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
