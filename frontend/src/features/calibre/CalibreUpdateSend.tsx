import { Send } from "lucide-react";
import { AppModal, ModalActions } from "../../components/AppModal";
import { useState } from "react";
import {
  Alert,
  Collapse,
  Button,
  Checkbox,
  MultiSelect,
  Select,
  Stack,
} from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/Auth";
import { Failure } from "../../components/Feedback";
import { post } from "../downloads/shared";
interface File {
  recordId: string;
  index: number;
  name: string;
  size: number;
  expectedSize: number;
}
interface Book {
  calibreId: number;
  eplId: number;
  title: string;
  revision: number;
  previous: { id: number; revision: string }[];
  files: File[];
  problem: string | null;
}
interface Choice {
  calibreId: number;
  revision: number;
  send: boolean;
  files: { recordId: string; index: number }[];
  cleanup: string;
  previous: number[];
  confirmBookDelete: boolean;
}
interface Result {
  downloadJobId: string | null;
  calibreJobId: string | null;
}
export function CalibreUpdateSend({ selected }: { selected: number[] }) {
  const { t } = useTranslation();
  const auth = useAuth();
  const cache = useQueryClient();
  const [customize, setCustomize] = useState(false);
  const [cleanup, setCleanup] = useState("KEEP");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [requestId, setRequestId] = useState("");
  const [opened, setOpened] = useState(false),
    [books, setBooks] = useState<Book[]>([]),
    [choices, setChoices] = useState<Choice[]>([]),
    [download, setDownload] = useState(false),
    [deferred, setDeferred] = useState(false);
  const prepare = useMutation({
    mutationFn: () =>
      post<Book[]>("/calibre/updates/prepare", { ids: selected }),
    retry: false,
    onSuccess: (data) => {
      setBooks(data);
      setChoices(
        data.map((b) => ({
          calibreId: b.calibreId,
          revision: b.revision,
          send: b.files.length > 0,
          files: b.files.length
            ? [{ recordId: b.files[0].recordId, index: b.files[0].index }]
            : [],
          cleanup: "KEEP",
          previous: b.previous.map((p) => p.id),
          confirmBookDelete: false,
        })),
      );
      setCustomize(false);
      setCleanup("KEEP");
      setConfirmDelete(false);
      setDownload(false);
      setDeferred(false);
      send.reset();
    },
  });
  const send = useMutation({
    mutationFn: () =>
      post<Result>("/calibre/updates", {
        id: requestId,
        books: choices.map((c, n) =>
          !books[n].files.length && !deferred
            ? { ...c, cleanup: "KEEP", previous: [], confirmBookDelete: false }
            : c,
        ),
        download,
        deferred,
      }),
    retry: false,
    onSuccess: () => {
      void cache.invalidateQueries({ queryKey: ["calibre-jobs"] });
      void cache.invalidateQueries({ queryKey: ["jobs"] });
      setOpened(false);
    },
  });
  if (
    !selected.length ||
    !auth.can("CALIBRE_SEND") ||
    !auth.can("DOWNLOADS_FILES_READ") ||
    !auth.can("CATALOG_READ")
  )
    return null;
  function change(index: number, patch: Partial<Choice>) {
    setRequestId(`${Date.now()}-${Math.random().toString(36).slice(2)}`);
    setChoices((old) =>
      old.map((c, n) => (n === index ? { ...c, ...patch } : c)),
    );
    send.reset();
  }
  const cleanupOptions = [
    { value: "KEEP", label: t("calibreUpdateSend.keep") },
    { value: "EPUB", label: t("calibreUpdateSend.removeEpub") },
    { value: "BOOK", label: t("calibreUpdateSend.removeBook") },
  ];
  const ready = books.filter((b, n) => b.files.length > 0 && choices[n]?.send);
  const missing = books.filter((b) => !b.files.length && !b.problem);
  function applyCleanup(value: string, confirmed = false) {
    setCleanup(value);
    setConfirmDelete(confirmed);
    setRequestId(`${Date.now()}-${Math.random().toString(36).slice(2)}`);
    setChoices((old) =>
      old.map((c, n) => ({
        ...c,
        cleanup: books[n].previous.length ? value : "KEEP",
        confirmBookDelete: confirmed,
      })),
    );
    send.reset();
  }
  return (
    <>
      <Button
        disabled={!selected.length}
        onClick={() => {
          setRequestId(`${Date.now()}-${Math.random().toString(36).slice(2)}`);
          setOpened(true);
          setBooks([]);
          send.reset();
          prepare.mutate();
        }}
      >
        {t("calibreUpdateSend.open", { count: selected.length })}
      </Button>
      <AppModal
        icon={Send}
        className="calibre-update-dialog"
        opened={opened}
        onClose={() => {
          if (!send.isPending) setOpened(false);
        }}
        title={t("calibreUpdateSend.heading")}
        width="wide"
        closeOnClickOutside={!send.isPending}
        closeOnEscape={!send.isPending}
      >
        <Stack gap="sm">
          {prepare.isPending && <p>{t("calibreUpdateSend.preparing")}</p>}
          {prepare.isError && (
            <Failure error={prepare.error} retry={() => prepare.mutate()} />
          )}
          {books.length > 0 && !send.isSuccess && (
            <>
              {ready.length > 0 && (
                <fieldset className="send-options-group">
                  <legend>{t("calibreUpdateSend.sendNow")}</legend>
                  <p className="calibre-update-summary">
                    {t("calibreUpdateSend.readySummary", {
                      count: ready.length,
                    })}
                  </p>
                </fieldset>
              )}
              {books
                .filter((b) => b.problem)
                .map((b) => (
                  <Alert key={b.calibreId} color="red">
                    {b.eplId} · {b.title}: {b.problem}
                  </Alert>
                ))}
              {missing.length > 0 && (
                <fieldset
                  className="send-options-group"
                  disabled={send.isPending}
                >
                  <legend>{t("calibreUpdateSend.pendingDownloads")}</legend>
                  <Stack gap="sm">
                    <p className="calibre-update-summary">
                      {t("calibreUpdateSend.missingSummary", {
                        count: missing.length,
                      })}
                    </p>
                    <Checkbox
                      label={t("calibreUpdateSend.download")}
                      checked={download}
                      disabled={!auth.can("TORRENT_SEND") || send.isPending}
                      onChange={(e) => {
                        const value = e.currentTarget.checked;
                        setRequestId(
                          `${Date.now()}-${Math.random().toString(36).slice(2)}`,
                        );
                        setDownload(value);
                        setChoices((old) =>
                          old.map((c, n) =>
                            books[n].files.length
                              ? c
                              : { ...c, send: value && !books[n].problem },
                          ),
                        );
                      }}
                    />
                    {download && (
                      <Select
                        label={t("calibreUpdateSend.afterDownload")}
                        allowDeselect={false}
                        value={deferred ? "send" : "download"}
                        data={[
                          {
                            value: "download",
                            label: t("calibreUpdateSend.onlyDownload"),
                          },
                          {
                            value: "send",
                            label: t("calibreUpdateSend.deferred"),
                            disabled: !auth.can("TORRENT_SYNC"),
                          },
                        ]}
                        onChange={(v) => {
                          setRequestId(
                            `${Date.now()}-${Math.random().toString(36).slice(2)}`,
                          );
                          setDeferred(v === "send");
                        }}
                        disabled={send.isPending}
                      />
                    )}
                  </Stack>
                </fieldset>
              )}
              {auth.can("CALIBRE_DELETE") &&
                (ready.length > 0 || (download && deferred)) && (
                  <fieldset className="send-options-group">
                    <legend>{t("calibreUpdateSend.previousSection")}</legend>
                    <Stack gap="sm">
                      <Select
                        label={t("calibreUpdateSend.cleanup")}
                        value={cleanup}
                        allowDeselect={false}
                        data={cleanupOptions}
                        disabled={send.isPending || customize}
                        onChange={(value) => applyCleanup(value ?? "KEEP")}
                      />
                      {!customize && cleanup === "BOOK" && (
                        <Checkbox
                          label={t("calibreUpdateSend.confirmDelete")}
                          checked={confirmDelete}
                          disabled={send.isPending}
                          onChange={(e) =>
                            applyCleanup(cleanup, e.currentTarget.checked)
                          }
                        />
                      )}
                      <Checkbox
                        label={t("calibreUpdateSend.customize")}
                        checked={customize}
                        disabled={send.isPending}
                        onChange={(e) => {
                          setCustomize(e.currentTarget.checked);
                          if (!e.currentTarget.checked)
                            applyCleanup(cleanup, confirmDelete);
                        }}
                      />
                    </Stack>
                  </fieldset>
                )}
              <p className="muted calibre-update-notes">
                {t("calibreUpdateSend.safety")}
              </p>
              {books.map((book, index) => {
                const choice = choices[index];
                const canClean =
                  book.files.length > 0 || (download && deferred);
                return (
                  <Collapse
                    key={book.calibreId}
                    in={
                      customize ||
                      book.files.length > 1 ||
                      book.files.some((f) => f.size !== f.expectedSize)
                    }
                  >
                    <fieldset
                      className="send-options-group"
                      key={book.calibreId}
                      disabled={send.isPending}
                    >
                      <legend>
                        {book.eplId} · {book.title} · r{book.revision}
                      </legend>
                      <Stack gap="sm">
                        <Checkbox
                          label={
                            book.files.length
                              ? t("calibreUpdateSend.sendNow")
                              : t("calibreUpdateSend.downloadBook")
                          }
                          checked={choice.send}
                          disabled={
                            !book.files.length && (!download || !!book.problem)
                          }
                          onChange={(e) =>
                            change(index, { send: e.currentTarget.checked })
                          }
                        />
                        {book.problem && (
                          <Alert color="red">{book.problem}</Alert>
                        )}
                        {book.files.length > 1 && (
                          <Select
                            label={t("calibreUpdateSend.file")}
                            allowDeselect={false}
                            value={String(
                              book.files.findIndex(
                                (f) =>
                                  f.recordId === choice.files[0]?.recordId &&
                                  f.index === choice.files[0]?.index,
                              ),
                            )}
                            data={book.files.map((f, n) => ({
                              value: String(n),
                              label: f.name,
                            }))}
                            onChange={(v) => {
                              const f = book.files[Number(v)];
                              change(index, {
                                files: [
                                  { recordId: f.recordId, index: f.index },
                                ],
                              });
                            }}
                          />
                        )}
                        {book.files.some((f) => f.size !== f.expectedSize) && (
                          <Alert color="yellow">
                            {t("calibreUpdateSend.sizeWarning")}
                          </Alert>
                        )}
                        {customize && (
                          <p>
                            {t("calibreUpdateSend.previous")}:{" "}
                            {book.previous
                              .map((p) => `${p.id} (r${p.revision})`)
                              .join(", ") || "—"}
                          </p>
                        )}
                        {customize &&
                          book.previous.length > 0 &&
                          auth.can("CALIBRE_DELETE") &&
                          canClean &&
                          choice.send && (
                            <>
                              <Select
                                label={t("calibreUpdateSend.cleanup")}
                                allowDeselect={false}
                                value={choice.cleanup}
                                data={[
                                  {
                                    value: "KEEP",
                                    label: t("calibreUpdateSend.keep"),
                                  },
                                  {
                                    value: "EPUB",
                                    label: t("calibreUpdateSend.removeEpub"),
                                  },
                                  {
                                    value: "BOOK",
                                    label: t("calibreUpdateSend.removeBook"),
                                  },
                                ]}
                                onChange={(v) =>
                                  change(index, {
                                    cleanup: v || "KEEP",
                                    confirmBookDelete: false,
                                  })
                                }
                              />
                              {choice.cleanup !== "KEEP" && (
                                <MultiSelect
                                  label={t("calibreUpdateSend.previous")}
                                  value={choice.previous.map(String)}
                                  data={book.previous.map((p) => ({
                                    value: String(p.id),
                                    label: `${p.id} · r${p.revision}`,
                                  }))}
                                  onChange={(v) =>
                                    change(index, { previous: v.map(Number) })
                                  }
                                />
                              )}
                              {choice.cleanup === "BOOK" && (
                                <Checkbox
                                  label={t("calibreUpdateSend.confirmDelete")}
                                  checked={choice.confirmBookDelete}
                                  onChange={(e) =>
                                    change(index, {
                                      confirmBookDelete:
                                        e.currentTarget.checked,
                                    })
                                  }
                                />
                              )}
                            </>
                          )}
                      </Stack>
                    </fieldset>
                  </Collapse>
                );
              })}
              <ModalActions
                summary={
                  <p>
                    {t("calibreUpdateSend.jobsSummary", {
                      calibre:
                        choices.filter(
                          (c, n) =>
                            c.send &&
                            (books[n].files.length > 0 ||
                              (download && deferred)),
                        ).length > 0
                          ? 1
                          : 0,
                      downloads:
                        download &&
                        choices.some(
                          (c, n) =>
                            c.send &&
                            !books[n].files.length &&
                            !books[n].problem,
                        )
                          ? 1
                          : 0,
                    })}
                  </p>
                }
              >
                <Button
                  variant="default"
                  disabled={send.isPending}
                  onClick={() => setOpened(false)}
                >
                  {t("common.cancel")}
                </Button>
                <Button
                  loading={send.isPending}
                  disabled={
                    !choices.some((c) => c.send) ||
                    choices.some(
                      (c, n) =>
                        c.send &&
                        (books[n].files.length > 0 || deferred) &&
                        c.cleanup !== "KEEP" &&
                        (!c.previous.length ||
                          (c.cleanup === "BOOK" && !c.confirmBookDelete)),
                    )
                  }
                  onClick={() => send.mutate()}
                >
                  {t("calibreUpdateSend.confirm")}
                </Button>
              </ModalActions>
            </>
          )}
          {send.isError && (
            <Failure error={send.error} retry={() => send.mutate()} />
          )}
        </Stack>
      </AppModal>
    </>
  );
}
