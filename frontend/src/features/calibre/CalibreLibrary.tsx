import { EpubBatchDownload } from "../downloads/EpubBatchDownload";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { CalibreTable, calibreColumns, type CalibreBook } from "./CalibreTable";
import { useNotifications } from "../notifications/Notifications";
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
}
export function CalibreLibrary() {
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
  const selectable = auth.can("CALIBRE_FILES_READ");
  const [selected, setSelected] = useState<number[]>([]);
  useEffect(
    () => setSelected([]),
    [page, size, title, author, eplId, comparison, presence, sort],
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
  });
  const result = useQuery({
    queryKey: ["calibre-library", params.toString(), sort],
    queryFn: ({ signal }) =>
      get<Page<CalibreBook>>(
        `/calibre/books?${params}&${sortQuery(sort)}`,
        signal,
      ),
  });
  useEffect(() => {
    if (result.data)
      setSelected((previous) =>
        previous.filter((id) =>
          result.data.items.some((book) => book.calibreId === id),
        ),
      );
  }, [result.data]);
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
    "eplsync.calibre.columns",
    calibreColumns,
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
        <h1>{t("calibre.libraryHeading")}</h1>
        {auth.can("CALIBRE_SYNC") && (
          <Button loading={sync.isPending} onClick={() => sync.mutate()}>
            {t("calibre.sync")}
          </Button>
        )}
      </div>
      <p className="muted">{t("calibre.libraryDescription")}</p>
      {snapshot.data ? (
        <p>
          {t("calibre.library")}: {snapshot.data.library} ·{" "}
          {t("calibre.lastSync")}: {date(snapshot.data.syncedAt)}
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
        <MultiSelect
          searchable
          styles={{ inputField: { flex: "1 1 40px", minWidth: 40 } }}
          label={t("calibre.comparison")}
          clearable
          value={comparison ? comparison.split(",") : []}
          data={["CURRENT", "OUTDATED", "NEWER", "UNKNOWN"].map((value) => ({
            value,
            label: t(`calibre.filter.${value}`),
          }))}
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
