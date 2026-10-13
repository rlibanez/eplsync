import { SelectAllRows } from "../../components/SelectAllRows";
import { EpubBatchDownload } from "../downloads/EpubBatchDownload";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { CalibreTable, calibreColumns, type CalibreBook } from "./CalibreTable";
import { useNotifications } from "../notifications/Notifications";
import { Link } from "react-router-dom";
import { useEffect, useState } from "react";
import { Button, MultiSelect, TextInput } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { useDownloadColumns } from "../downloads/DownloadTable";
import { sortQuery } from "../downloads/TableControls";
import { Paging, post, type Page } from "../downloads/shared";
interface Snapshot {
  library: string;
  syncedAt: string;
  total: number;
  identified: number;
  complete?: boolean;
}
export function CalibreUpdates() {
  return <CalibreLibrary updates />;
}
export function CalibreLibrary({ updates = false }: { updates?: boolean }) {
  const { t } = useTranslation();
  const auth = useAuth();
  const { notify } = useNotifications();
  const { date } = useLocale();
  const cache = useQueryClient();
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20),
    [title, setTitle] = useState(""),
    [author, setAuthor] = useState(""),
    [eplId, setEplId] = useState(""),
    [comparison, setComparison] = useState(""),
    [presence, setPresence] = useState(""),
    [sort, setSort] = useState("title,asc");
  const selectable = !updates && auth.can("CALIBRE_FILES_READ");
  const [selected, setSelected] = useState<number[]>([]);
  useEffect(
    () => setSelected([]),
    [title, author, eplId, comparison, presence, sort],
  );
  const pageSizeChoice = usePageSizeChoice(size);
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
    title,
    author,
    ...(eplId ? { eplId } : {}),
    comparison,
    presence,
    ...(updates ? { updatesOnly: "true" } : {}),
  });
  const result = useQuery({
    queryKey: ["calibre-library", params.toString(), sort, updates],
    queryFn: ({ signal }) =>
      get<Page<CalibreBook>>(
        `/calibre/books?${params}&${sortQuery(sort)}`,
        signal,
      ),
  });
  const snapshot = useQuery({
    queryKey: ["calibre-snapshot"],
    queryFn: ({ signal }) =>
      get<{ snapshot: Snapshot | null }>(
        "/settings/calibre/snapshot",
        signal,
      ).then((d) => d.snapshot),
  });
  const sync = useMutation({
    mutationFn: () => post<Snapshot>("/settings/calibre/sync", {}),
    retry: false,
    onSuccess: (data) => {
      cache.setQueryData(["calibre-snapshot"], data);
      void cache.invalidateQueries({ queryKey: ["calibre-library"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
      setPage(0);
      notify({
        title: "Calibre",
        message: t("calibre.synced"),
        tone: "success",
      });
    },
    onError: (error) =>
      notify({
        title: "Calibre",
        message:
          (error as { details?: string }).details || t("errors.unexpected"),
        tone: "error",
      }),
  });
  const columns = useDownloadColumns(
    updates ? "eplsync.calibre.updates.columns" : "eplsync.calibre.columns",
    updates
      ? calibreColumns.filter(
          (c) => !["actions", "presence", "comparison"].includes(c.field),
        )
      : calibreColumns,
    sort,
    (v) => {
      setSort(v);
      setPage(0);
    },
    {
      defaults: "title,asc",
      selection: selectable,
    },
  );
  return (
    <>
      <div className="page-heading">
        <h1>
          {t(updates ? "calibre.updatesHeading" : "calibre.libraryHeading")}
        </h1>
        {auth.can("CALIBRE_SYNC") && (
          <div className="action-row">
            {!updates && (
              <Button
                component={Link}
                to="/calibre/updates"
                disabled={sync.isPending}
              >
                {t("revisionUpdates.search")}
              </Button>
            )}
            <Button loading={sync.isPending} onClick={() => sync.mutate()}>
              {t(updates ? "revisionUpdates.search" : "calibre.sync")}
            </Button>
          </div>
        )}
      </div>
      <p className="muted">
        {t(
          updates ? "calibre.updatesDescription" : "calibre.libraryDescription",
        )}
      </p>
      {snapshot.data?.complete === false && <p>{t("calibreSend.partial")}</p>}
      {snapshot.data ? (
        <p className="muted">
          {t("calibre.lastSync")}: {date(snapshot.data.syncedAt)}
          {!updates && (
            <>
              {" "}
              · {t("calibre.library")}: {snapshot.data.library}
            </>
          )}
        </p>
      ) : (
        !snapshot.isPending &&
        !snapshot.isError && <p>{t("calibre.notSynced")}</p>
      )}
      {snapshot.isError && (
        <Failure error={snapshot.error} retry={() => snapshot.refetch()} />
      )}
      <div className="filters jobs-filters">
        <TextInput
          label={t("filters.eplId")}
          value={eplId}
          inputMode="numeric"
          onChange={(e) => {
            setEplId(e.currentTarget.value.replace(/\D/g, ""));
            setPage(0);
          }}
        />
        <TextInput
          label={t("downloads.book")}
          value={title}
          maxLength={256}
          onChange={(e) => {
            setTitle(e.currentTarget.value);
            setPage(0);
          }}
        />
        <TextInput
          label={t("filters.author")}
          value={author}
          maxLength={256}
          onChange={(e) => {
            setAuthor(e.currentTarget.value);
            setPage(0);
          }}
        />
        {!updates && (
          <>
            <MultiSelect
              searchable
              styles={{ inputField: { flex: "1 1 40px", minWidth: 40 } }}
              label={t("calibre.comparison")}
              clearable
              value={comparison ? comparison.split(",") : []}
              data={["CURRENT", "OUTDATED", "NEWER", "UNKNOWN"].map(
                (value) => ({
                  value,
                  label: t(`calibre.filter.${value}`),
                }),
              )}
              onChange={(v) => {
                setComparison(v.join(","));
                setPage(0);
              }}
            />
            <MultiSelect
              searchable
              styles={{ inputField: { flex: "1 1 40px", minWidth: 40 } }}
              label={t("calibre.presence")}
              clearable
              value={presence ? presence.split(",") : []}
              data={["PRESENT", "MISSING", "UNIDENTIFIED"].map((value) => ({
                value,
                label: t(`calibre.presenceValues.${value}`),
              }))}
              onChange={(v) => {
                setPresence(v.join(","));
                setPage(0);
              }}
            />
          </>
        )}
        <Button
          variant="subtle"
          onClick={() => {
            setTitle("");
            setAuthor("");
            setEplId("");
            setComparison("");
            setPresence("");
            setPage(0);
          }}
        >
          {t("catalog.clear")}
        </Button>
      </div>
      <section className="panel">
        <div className="table-toolbar">
          {selectable && (
            <SelectAllRows<CalibreBook, number>
              url={`/calibre/books?${new URLSearchParams([...params].filter(([key]) => key !== "page" && key !== "size"))}&${sortQuery(sort)}`}
              total={result.data?.meta.totalItems ?? 0}
              identify={(book) => book.calibreId}
              onSelect={setSelected}
            />
          )}
          <div className="catalog-table-controls">{columns.controls}</div>
        </div>
        {selectable && (
          <EpubBatchDownload
            selected={selected}
            endpoint="/calibre/epub-downloads"
            selectionLabel="calibre.selectedBooks"
          />
        )}
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <>
            <CalibreTable
              items={result.data.items}
              excludedFields={
                updates ? ["actions", "presence", "comparison"] : []
              }
              columns={columns}
              selected={selectable ? selected : undefined}
              onSelection={setSelected}
            />
            <Paging
              choice={pageSizeChoice}
              meta={result.data.meta}
              page={page}
              size={size}
              maxSize={1000}
              onPage={setPage}
              onSize={(v) => {
                setSize(v);
                setPage(0);
              }}
            />
          </>
        )}
      </section>
    </>
  );
}
