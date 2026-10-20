import { CompleteCalibreIdentifiers } from "./CompleteCalibreIdentifiers";
import { useBookViewState } from "../catalog/useBookViewState";
import {
  textOperatorProps,
  stateOperatorProps,
} from "../../components/StateOperator";
import {
  CalibreRemovalLauncher,
  CleanupLauncher,
} from "../cleanup/RevisionCleanup";
import { PageTitle } from "../../components/PageTitle";
import { SelectionToolbar } from "../../components/SelectionToolbar";
import {
  isSavedSearchChanged,
  latestSavedSearch,
  savedSearchPage,
  savedSearchUrl,
} from "../downloads/SavedUpdateSearch";
import { CalibreUpdateSend } from "./CalibreUpdateSend";
import { SelectAllRows } from "../../components/SelectAllRows";
import { EpubBatchDownload } from "../downloads/EpubBatchDownload";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { CalibreTable, calibreColumns, type CalibreBook } from "./CalibreTable";
import { Link, useSearchParams } from "react-router-dom";
import { useEffect, useRef, useState } from "react";
import {
  Button,
  CloseButton,
  MultiSelect,
  Select,
  TextInput,
  TagsInput,
} from "@mantine/core";
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
  const { date } = useLocale();
  const cache = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const searchHandled = useRef(false);
  const [page, setPage] = useBookViewState("page", 0),
    [size, setSize] = useBookViewState("size", 20),
    [title, setTitle] = useBookViewState("title", ""),
    [author, setAuthor] = useBookViewState("author", ""),
    [eplId, setEplId] = useBookViewState("eplId", ""),
    [comparison, setComparison] = useBookViewState("comparison", ""),
    [presence, setPresence] = useBookViewState("presence", ""),
    [sort, setSort] = useBookViewState("sort", "title,asc");
  const selectable = updates
    ? auth.can("CALIBRE_SEND") &&
      auth.can("DOWNLOADS_FILES_READ") &&
      auth.can("CATALOG_READ")
    : auth.can("CALIBRE_FILES_READ") || auth.can("CALIBRE_DELETE");
  const [excluded, setExcluded] = useBookViewState("excluded", {
    title: false,
    author: false,
    eplId: false,
    comparison: false,
    presence: false,
  });
  const [selected, setSelected] = useState<number[]>([]);
  const [searching, setSearching] = useState(false);
  useEffect(
    () => setSelected([]),
    [title, author, excluded, eplId, comparison, presence, sort],
  );
  const pageSizeChoice = usePageSizeChoice(size);
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
    title,
    author,
    ...(title.trim() && excluded.title ? { titleOperator: "ne" } : {}),
    ...(author.trim() && excluded.author ? { authorOperator: "ne" } : {}),
    ...(eplId ? { eplId } : {}),
    ...(!updates && eplId && excluded.eplId ? { eplIdOperator: "ne" } : {}),
    ...(!updates && comparison && excluded.comparison
      ? { comparisonOperator: "ne" }
      : {}),
    ...(!updates && presence && excluded.presence
      ? { presenceOperator: "ne" }
      : {}),
    comparison,
    presence,
    ...(updates ? { updatesOnly: "true" } : {}),
  });
  const [availability, setAvailability] = useBookViewState<string | null>(
    "availability",
    null,
  );
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
    queryFn: ({ signal }) => latestSavedSearch("calibre", signal),
    refetchInterval: 30000,
    enabled: updates && !searching,
  });
  useEffect(() => {
    if (updates) {
      setSelected([]);
      setPage(0);
    }
  }, [updates, previousSearch.data?.searchedAt]);
  const savedUrl = savedSearchUrl(
    "calibre",
    previousSearch.data?.searchedAt ?? 0,
    sort,
    {
      title,
      author,
      titleOperator: title.trim() && excluded.title ? "ne" : "",
      authorOperator: author.trim() && excluded.author ? "ne" : "",
      eplId,
      availability: availability ?? "",
    },
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
    enabled: !updates || (!searching && previousSearch.data !== undefined),
    retry: updates ? false : undefined,
    queryFn: ({ signal }) =>
      updates
        ? savedSearchPage<CalibreBook>(savedUrl, page, size, signal)
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
  useEffect(() => {
    if (updates && !searching && isSavedSearchChanged(result.error)) {
      setSelected([]);
      void previousSearch.refetch();
    }
  }, [updates, result.error, searching]);
  const sync = useMutation({
    onMutate: async () => {
      if (!updates) return;
      setSearching(true);
      setSelected([]);
      await Promise.all([
        cache.cancelQueries({
          queryKey: ["saved-calibre-updates", auth.user?.id],
        }),
        cache.cancelQueries({ queryKey: ["calibre-library", auth.user?.id] }),
      ]);
    },
    mutationFn: async (): Promise<{
      snapshot: Snapshot;
      search?: import("../downloads/SavedUpdateSearch").SavedSearch;
    }> => {
      if (!updates)
        return { snapshot: await post<Snapshot>("/settings/calibre/sync", {}) };
      return post<{
        snapshot: Snapshot;
        search: import("../downloads/SavedUpdateSearch").SavedSearch;
      }>("/settings/tasks/searches/calibre", {});
    },
    retry: false,
    onSuccess: ({ snapshot, search }) => {
      setPage(0);
      setSelected([]);
      if (search)
        cache.setQueryData(["saved-calibre-updates", auth.user?.id], search);
      cache.setQueryData(["calibre-snapshot"], snapshot);
      void cache.invalidateQueries({
        queryKey: ["calibre-library"],
        refetchType: updates ? "none" : "active",
      });
      void cache.invalidateQueries({ queryKey: ["book"] });
    },
    onSettled: () => setSearching(false),
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
    (page + 1) * size,
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
        {(auth.can("CALIBRE_SYNC") ||
          (!updates && auth.can("CALIBRE_DELETE"))) && (
          <div className="action-row">
            {!updates && (
              <CleanupLauncher source="calibre" selected={selected} />
            )}
            {!updates && auth.can("CALIBRE_SYNC") && (
              <Button
                component={Link}
                to="/calibre/updates?search=1"
                disabled={sync.isPending}
              >
                {t("revisionUpdates.search")}
              </Button>
            )}
            {auth.can("CALIBRE_SYNC") && (
              <Button loading={sync.isPending} onClick={() => sync.mutate()}>
                {t(updates ? "revisionUpdates.search" : "calibre.sync")}
              </Button>
            )}
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
      {sync.isError && (
        <Failure error={sync.error} retry={() => sync.mutate()} />
      )}
      {snapshot.isError && (
        <Failure error={snapshot.error} retry={() => snapshot.refetch()} />
      )}
      <div className="filters jobs-filters">
        {updates ? (
          <TextInput
            label={t("filters.eplId")}
            value={eplId}
            {...(!updates
              ? stateOperatorProps(excluded.eplId, () => {
                  setExcluded((current) => ({
                    ...current,
                    eplId: !current.eplId,
                  }));
                  setPage(0);
                })
              : {})}
            rightSectionPointerEvents="auto"
            rightSection={
              eplId ? (
                <CloseButton
                  size="sm"
                  aria-label={`${t("catalog.clear")}: ${t("filters.eplId")}`}
                  onClick={() => {
                    setEplId("");
                    setExcluded((current) => ({ ...current, eplId: false }));
                    setPage(0);
                  }}
                />
              ) : undefined
            }
            inputMode="numeric"
            onChange={(e) => {
              setEplId(e.currentTarget.value.replace(/\D/g, ""));
              if (!e.currentTarget.value.replace(/\D/g, ""))
                setExcluded((current) => ({ ...current, eplId: false }));
              setPage(0);
            }}
          />
        ) : (
          <TagsInput
            label={t("filters.eplId")}
            value={eplId ? eplId.split(/[,;\s]+/).filter(Boolean) : []}
            inputMode="numeric"
            splitChars={[",", ";", " "]}
            clearable
            clearButtonProps={{
              "aria-hidden": false,
              tabIndex: 0,
              "aria-label": `${t("catalog.clear")}: ${t("filters.eplId")}`,
            }}
            {...stateOperatorProps(excluded.eplId, () => {
              setExcluded((current) => ({ ...current, eplId: !current.eplId }));
              setPage(0);
            })}
            onChange={(ids) => {
              setEplId(ids.join(","));
              if (!ids.length)
                setExcluded((current) => ({ ...current, eplId: false }));
              setPage(0);
            }}
          />
        )}
        <TextInput
          label={t("downloads.book")}
          value={title}
          rightSectionPointerEvents="auto"
          rightSection={
            title ? (
              <CloseButton
                size="sm"
                aria-label={`${t("catalog.clear")}: ${t("downloads.book")}`}
                onClick={() => {
                  setTitle("");
                  setExcluded((current) => ({ ...current, title: false }));
                  setPage(0);
                }}
              />
            ) : undefined
          }
          {...textOperatorProps(excluded.title, () => {
            setExcluded((current) => ({
              ...current,
              title: !current.title,
            }));
            setPage(0);
          })}
          maxLength={256}
          onChange={(e) => {
            setTitle(e.currentTarget.value);
            if (!e.currentTarget.value.trim())
              setExcluded((current) => ({ ...current, title: false }));
            setPage(0);
          }}
        />
        <TextInput
          label={t("filters.author")}
          value={author}
          rightSectionPointerEvents="auto"
          rightSection={
            author ? (
              <CloseButton
                size="sm"
                aria-label={`${t("catalog.clear")}: ${t("filters.author")}`}
                onClick={() => {
                  setAuthor("");
                  setExcluded((current) => ({ ...current, author: false }));
                  setPage(0);
                }}
              />
            ) : undefined
          }
          {...textOperatorProps(excluded.author, () => {
            setExcluded((current) => ({
              ...current,
              author: !current.author,
            }));
            setPage(0);
          })}
          maxLength={256}
          onChange={(e) => {
            setAuthor(e.currentTarget.value);
            if (!e.currentTarget.value.trim())
              setExcluded((current) => ({ ...current, author: false }));
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
              {...stateOperatorProps(excluded.comparison, () => {
                setExcluded((current) => ({
                  ...current,
                  comparison: !current.comparison,
                }));
                setPage(0);
              })}
              clearButtonProps={{
                "aria-hidden": false,
                tabIndex: 0,
                "aria-label": `${t("catalog.clear")}: ${t("calibre.comparison")}`,
              }}
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
                if (!v.length)
                  setExcluded((current) => ({ ...current, comparison: false }));
                setPage(0);
              }}
            />
            <MultiSelect
              searchable
              styles={{ inputField: { flex: "1 1 40px", minWidth: 40 } }}
              label={t("calibre.presence")}
              {...stateOperatorProps(excluded.presence, () => {
                setExcluded((current) => ({
                  ...current,
                  presence: !current.presence,
                }));
                setPage(0);
              })}
              clearButtonProps={{
                "aria-hidden": false,
                tabIndex: 0,
                "aria-label": `${t("catalog.clear")}: ${t("calibre.presence")}`,
              }}
              clearable
              value={presence ? presence.split(",") : []}
              data={["PRESENT", "MISSING", "UNIDENTIFIED"].map((value) => ({
                value,
                label: t(`calibre.presenceValues.${value}`),
              }))}
              onChange={(v) => {
                setPresence(v.join(","));
                if (!v.length)
                  setExcluded((current) => ({ ...current, presence: false }));
                setPage(0);
              }}
            />
          </>
        )}
        <Button
          variant="subtle"
          onClick={() => {
            setTitle("");
            setExcluded({
              title: false,
              author: false,
              eplId: false,
              comparison: false,
              presence: false,
            });
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
                url={
                  updates
                    ? savedUrl
                    : `/calibre/books?${new URLSearchParams([...params].filter(([key]) => key !== "page" && key !== "size"))}&${sortQuery(sort)}`
                }
                total={result.data?.meta.totalItems ?? 0}
                identify={(book) => book.calibreId}
                onSelect={setSelected}
              />
            )
          }
          actions={
            updates ? (
              <CalibreUpdateSend selected={selected} />
            ) : (
              <>
                {auth.can("CALIBRE_FILES_READ") && (
                  <EpubBatchDownload
                    selected={selected}
                    endpoint="/calibre/epub-downloads"
                    toolbar={false}
                  />
                )}
                <CompleteCalibreIdentifiers selected={selected} />
                <CalibreRemovalLauncher selected={selected} />
              </>
            )
          }
        />
        {searching || result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <>
            <CalibreTable
              offset={page * size}
              items={result.data.items}
              emptyMessage={updates ? t("revisionUpdates.empty") : undefined}
              excludedFields={excludedFields}
              columns={columns}
              selected={selectable ? selected : undefined}
              selectionScope={`${params}:${sort}:${updates}`}
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
