import { PageTitle } from "../../components/PageTitle";
import { SelectionToolbar } from "../../components/SelectionToolbar";
import { latestSavedSearch, searchPage } from "../downloads/SavedUpdateSearch";
import { CalibreUpdateSend } from "./CalibreUpdateSend";
import { SelectAllRows } from "../../components/SelectAllRows";
import { EpubBatchDownload } from "../downloads/EpubBatchDownload";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { CalibreTable, calibreColumns, type CalibreBook } from "./CalibreTable";
import { useNotifications } from "../notifications/Notifications";
import { Link, useSearchParams } from "react-router-dom";
import { useEffect, useRef, useState } from "react";
import { Button, MultiSelect, Select, TextInput } from "@mantine/core";
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
  const [searchParams, setSearchParams] = useSearchParams();
  const searchHandled = useRef(false);
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20),
    [title, setTitle] = useState(""),
    [author, setAuthor] = useState(""),
    [eplId, setEplId] = useState(""),
    [comparison, setComparison] = useState(""),
    [presence, setPresence] = useState(""),
    [sort, setSort] = useState("title,asc");
  const selectable = updates
    ? auth.can("CALIBRE_SEND") &&
      auth.can("DOWNLOADS_FILES_READ") &&
      auth.can("CATALOG_READ")
    : auth.can("CALIBRE_FILES_READ");
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
  const [availability, setAvailability] = useState<string | null>(null);
  const canCheckFiles =
    auth.can("DOWNLOADS_FILES_READ") && auth.can("CATALOG_READ");
  const excludedFields = updates
    ? [
        "actions",
        "presence",
        "comparison",
        ...(!canCheckFiles ? ["downloadAvailability"] : []),
      ]
    : ["downloadAvailability"];
  const previousSearch = useQuery({
    queryKey: ["saved-calibre-updates", auth.user?.id],
    queryFn: ({ signal }) => latestSavedSearch<CalibreBook>("calibre", signal),
    refetchInterval: 30000,
    enabled: updates,
  });
  useEffect(() => {
    if (updates) setSelected([]);
  }, [updates, previousSearch.data?.searchedAt]);
  const filteredSavedRows = () =>
    (previousSearch.data?.rows ?? []).filter(
      (book) =>
        (!title ||
          book.title.toLocaleLowerCase().includes(title.toLocaleLowerCase())) &&
        (!author ||
          book.authors
            .toLocaleLowerCase()
            .includes(author.toLocaleLowerCase())) &&
        (!eplId || String(book.eplId) === eplId) &&
        (!availability ||
          (book.downloadAvailability ?? "UNKNOWN") === availability),
    );
  const result = useQuery({
    queryKey: [
      "calibre-library",
      auth.user?.id,
      params.toString(),
      sort,
      updates,
      previousSearch.data?.searchedAt,
      availability,
    ],
    queryFn: ({ signal }) =>
      updates
        ? Promise.resolve(searchPage(filteredSavedRows(), page, size, sort))
        : get<Page<CalibreBook>>(
            `/calibre/books?${params}&${sortQuery(sort)}`,
            signal,
          ),
  });
  const showSelectionActions =
    selectable && (!updates || (result.data?.meta.totalItems ?? 0) > 0);
  const snapshot = useQuery({
    queryKey: ["calibre-snapshot"],
    queryFn: ({ signal }) =>
      get<{ snapshot: Snapshot | null }>(
        "/settings/calibre/snapshot",
        signal,
      ).then((d) => d.snapshot),
  });
  const sync = useMutation({
    mutationFn: async () => {
      if (!updates) return post<Snapshot>("/settings/calibre/sync", {});
      const result = await post<{
        snapshot: Snapshot;
        search: import("../downloads/SavedUpdateSearch").SavedSearch<CalibreBook>;
      }>("/settings/tasks/searches/calibre", {});
      cache.setQueryData(
        ["saved-calibre-updates", auth.user?.id],
        result.search,
      );
      return result.snapshot;
    },
    retry: false,
    onSuccess: (data) => {
      cache.setQueryData(["calibre-snapshot"], data);
      void cache.invalidateQueries({ queryKey: ["calibre-library"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
      setPage(0);
      setSelected([]);
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
  useEffect(() => {
    if (!updates || searchParams.get("search") !== "1") {
      searchHandled.current = false;
      return;
    }
    if (searchHandled.current) return;
    searchHandled.current = true;
    const next = new URLSearchParams(searchParams);
    next.delete("search");
    setSearchParams(next, { replace: true });
    if (auth.can("CALIBRE_SYNC")) sync.mutate();
  }, [updates, searchParams, setSearchParams, auth, sync.mutate]);
  const columns = useDownloadColumns(
    updates ? "eplsync.calibre.updates.columns" : "eplsync.calibre.columns",
    calibreColumns.filter((c) => !excludedFields.includes(c.field)),
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
        <PageTitle
          title={t(
            updates ? "calibre.updatesHeading" : "calibre.libraryHeading",
          )}
          description={t(
            updates
              ? "calibre.updatesDescription"
              : "calibre.libraryDescription",
          )}
        />
        {auth.can("CALIBRE_SYNC") && (
          <div className="action-row">
            {!updates && (
              <Button
                component={Link}
                to="/calibre/updates?search=1"
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
      {snapshot.data?.complete === false && <p>{t("calibreSend.partial")}</p>}
      {updates ? (
        previousSearch.data && (
          <p className="muted">
            {t("revisionUpdates.lastSearch", {
              date: date(
                new Date(previousSearch.data.searchedAt).toISOString(),
              ),
            })}
          </p>
        )
      ) : snapshot.data ? (
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
      {updates && previousSearch.isError && (
        <Failure
          error={previousSearch.error}
          retry={() => previousSearch.refetch()}
        />
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
        {updates && canCheckFiles && (
          <Select
            label={t("calibreUpdateSend.availability")}
            clearable
            value={availability}
            data={["READY", "MISSING", "INACCESSIBLE", "UNKNOWN"].map(
              (value) => ({
                value,
                label: t(`calibreUpdateSend.availabilityValues.${value}`),
              }),
            )}
            onChange={(value) => {
              setAvailability(value);
              setPage(0);
              setSelected([]);
            }}
          />
        )}
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
            setAvailability(null);
            setPage(0);
          }}
        >
          {t("catalog.clear")}
        </Button>
      </div>
      <section className="panel">
        <SelectionToolbar
          count={selected.length}
          onClear={() => setSelected([])}
          controls={columns.controls}
          selectAll={
            showSelectionActions && (
              <SelectAllRows<CalibreBook, number>
                url={`/calibre/books?${new URLSearchParams([...params].filter(([key]) => key !== "page" && key !== "size"))}&${sortQuery(sort)}`}
                total={result.data?.meta.totalItems ?? 0}
                identify={(book) => book.calibreId}
                onSelect={setSelected}
                loadPage={
                  updates
                    ? async (url) => {
                        const params = new URLSearchParams(url.split("?")[1]);
                        return searchPage(
                          filteredSavedRows(),
                          Number(params.get("page")),
                          Number(params.get("size")),
                          sort,
                        );
                      }
                    : undefined
                }
              />
            )
          }
          actions={
            updates ? (
              <CalibreUpdateSend selected={selected} />
            ) : (
              selectable && (
                <EpubBatchDownload
                  selected={selected}
                  endpoint="/calibre/epub-downloads"
                  toolbar={false}
                />
              )
            )
          }
        />
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <>
            <CalibreTable
              items={result.data.items}
              emptyMessage={updates ? t("revisionUpdates.empty") : undefined}
              excludedFields={excludedFields}
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
