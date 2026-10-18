import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button, Checkbox, Progress } from "@mantine/core";
import { Tags } from "lucide-react";
import { useTranslation } from "react-i18next";
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
    [progress, setProgress] = useState(0);
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
    setRows([]);
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
        <fieldset className="send-options-group">
          <legend>{t("calibreIdentifiers.books")}</legend>
          <ul className="identifier-preview-list">
            {rows.map((row) => (
              <li key={row.calibreId}>
                <Checkbox
                  aria-label={`${t("calibreIdentifiers.select")} ${row.calibreId}`}
                  label={
                    <>
                      {row.title} · Calibre ID {row.calibreId} · EPL ID{" "}
                      {row.eplId ?? "—"} · {t("calibreIdentifiers.revision")}{" "}
                      {row.revision ?? "—"}
                    </>
                  }
                  description={
                    <span role={row.done ? "status" : undefined}>
                      {row.message}
                    </span>
                  }
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
              </li>
            ))}
          </ul>
        </fieldset>
        <ModalActions>
          <Button variant="default" onClick={close}>
            {t("common.close")}
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
