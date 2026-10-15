import { PageTitle } from "../../components/PageTitle";
import { SelectionToolbar } from "../../components/SelectionToolbar";
import { latestSavedSearch, searchPage } from "./SavedUpdateSearch";
import { SelectAllRows } from "../../components/SelectAllRows";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { useUpdateSession } from "./useUpdateSession";
import { BookCover } from "../catalog/BookCover";
import { useDownloadColumns } from "./DownloadTable";
import { sortQuery } from "./TableControls";
import { useEffect, useState } from "react";
import { Button, Checkbox, MultiSelect } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { RefreshCw } from "lucide-react";
import { useAuth } from "../auth/Auth";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, post, downloadStates } from "./shared";
import { SendSelection } from "./SendSelection";
interface Preferences {
  states: string[];
}
interface Update {
  eplId: number;
  title: string;
  coverUrl: string | null;
  coverAvailable: boolean | null;
  registeredRevision: number;
  availableRevision: number;
  status: string;
}
export function RevisionUpdates() {
  const { t } = useTranslation();
  const { status, date, revision } = useLocale();
  const auth = useAuth();
  const cache = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [opened, setOpened] = useState(searchParams.get("search") === "1");
  const [options, setOptions] = useState<string[]>([]);
  const session = useUpdateSession();
  const { search, page, size, sort, registeredStatus } = session;
  const setPage = (page: number) => session.setView({ page });
  const setSize = (size: number) => session.setView({ size });
  const pageSizeChoice = usePageSizeChoice(size);
  const setSort = (sort: string) => session.setView({ sort });
  const setRegisteredStatus = (registeredStatus: string | null) =>
    session.setView({ registeredStatus });
  const [selected, setSelected] = useState<number[]>([]);
  const [sending, setSending] = useState(false);
  const defaults = useQuery({
    queryKey: ["update-preferences"],
    queryFn: ({ signal }) =>
      get<Preferences>("/settings/downloads/updates", signal),
  });
  useEffect(() => {
    if (searchParams.get("search") !== "1" || !defaults.data) return;
    setOptions([...defaults.data.states]);
    setOpened(true);
    const next = new URLSearchParams(searchParams);
    next.delete("search");
    setSearchParams(next, { replace: true });
  }, [defaults.data, searchParams, setSearchParams]);
  const previousSearch = useQuery({
    queryKey: ["saved-download-updates", auth.user?.id],
    queryFn: ({ signal }) => latestSavedSearch<Update>("downloads", signal),
    refetchInterval: 30000,
  });
  useEffect(() => {
    if (previousSearch.data === null && search) {
      setSelected([]);
      session.setView({ page: 0, search: null });
      cache.removeQueries({ queryKey: ["revision-updates", auth.user?.id] });
      return;
    }
    if (
      previousSearch.data &&
      (!search || previousSearch.data.searchedAt > search.generation)
    ) {
      setSelected([]);
      session.setView({
        page: 0,
        search: {
          states: previousSearch.data.states ?? [],
          generation: previousSearch.data.searchedAt,
        },
      });
    }
  }, [previousSearch.data, search]);
  const savedPage = async (page: number, size: number) => {
    const saved = previousSearch.data;
    return searchPage(
      (saved?.rows ?? []).filter(
        (row) =>
          !registeredStatus || registeredStatus.split(",").includes(row.status),
      ),
      page,
      size,
      sort,
    );
  };
  const result = useQuery({
    queryKey: [
      "revision-updates",
      auth.user?.id,
      search?.generation,
      page,
      size,
      sort,
      registeredStatus,
    ],
    queryFn: () => savedPage(page, size),
    enabled: search !== null,
    staleTime: Infinity,
    gcTime: Infinity,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    retry: false,
  });
  useEffect(() => {
    // Keep the visible page for navigation, without accumulating every past page.
    if (result.data)
      cache.removeQueries({
        queryKey: ["revision-updates", auth.user?.id],
        predicate: (query) => query.getObserversCount() === 0,
      });
  }, [cache, auth.user?.id, result.data]);
  const find = useMutation({
    mutationKey: ["torrent-sync"],
    mutationFn: () =>
      post<import("./SavedUpdateSearch").SavedSearch<Update>>(
        "/settings/tasks/searches/downloads",
        { states: options },
      ),
    retry: false,
    onSuccess: (saved) => {
      const generation = saved.searchedAt;
      const data = searchPage(saved.rows, 0, size, sort);
      cache.setQueryData(["saved-download-updates", auth.user?.id], saved);
      cache.setQueryData(
        ["revision-updates", auth.user?.id, generation, 0, size, sort, null],
        data,
      );
      setPage(0);
      setRegisteredStatus(null);
      setSelected([]);
      session.setView({ search: { states: [...options], generation } });
      setOpened(false);
      void cache.invalidateQueries({ queryKey: ["downloads"] });
      void cache.invalidateQueries({ queryKey: ["download-summary"] });
      void cache.invalidateQueries({ queryKey: ["book-history"] });
    },
  });
  function toggle(id: number, checked: boolean) {
    setSelected((old) =>
      checked
        ? old.includes(id) || old.length >= 10000
          ? old
          : [...old, id]
        : old.filter((value) => value !== id),
    );
  }
  const columns = useDownloadColumns(
    "eplsync.updates.columnWidths.v3",
    [
      { field: "eplId", label: "revisionUpdates.eplId", width: 100 },
      { field: "title", label: "revisionUpdates.title", width: 360 },
      { field: "status", label: "revisionUpdates.status", width: 200 },
      {
        field: "registeredRevision",
        label: "revisionUpdates.registeredRevision",
        width: 190,
      },
      {
        field: "availableRevision",
        label: "revisionUpdates.availableRevision",
        width: 190,
      },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
      setSelected([]);
    },
    { defaults: "title,asc", selection: auth.can("TORRENT_SEND") },
  );
  const rows = result.data?.items ?? [];
  const allChecked =
    rows.length > 0 && rows.every((row) => selected.includes(row.eplId));
  return (
    <>
      <div className="page-heading">
        <PageTitle
          title={t("nav.updates")}
          description={t("revisionUpdates.subtitle")}
        />
        <Button
          disabled={
            !auth.can("TORRENT_SYNC") ||
            defaults.isPending ||
            defaults.isError ||
            find.isPending
          }
          onClick={() => {
            setOptions([...(defaults.data?.states ?? [])]);
            find.reset();
            setOpened(true);
          }}
        >
          {t("revisionUpdates.search")}
        </Button>
      </div>

      {!auth.can("TORRENT_SYNC") && (
        <p className="muted">{t("revisionUpdates.syncPermission")}</p>
      )}
      {defaults.isError && (
        <Failure error={defaults.error} retry={() => defaults.refetch()} />
      )}
      {search && (
        <p className="muted">
          {t("revisionUpdates.lastSearch", {
            date: date(new Date(search.generation).toISOString()),
          })}
        </p>
      )}
      <div className="filters jobs-filters">
        <MultiSelect
          searchable
          styles={{ inputField: { flex: "1 1 40px", minWidth: 40 } }}
          label={t("revisionUpdates.status")}
          clearable
          value={registeredStatus ? registeredStatus.split(",") : []}
          disabled={!search || find.isPending}
          data={downloadStates.map((value) => ({
            value,
            label: status(value),
          }))}
          onChange={(value) => {
            setRegisteredStatus(value.join(",") || null);
            setPage(0);
            setSelected([]);
          }}
        />
      </div>
      {previousSearch.isError && (
        <Failure
          error={previousSearch.error}
          retry={() => previousSearch.refetch()}
        />
      )}
      <section className="panel">
        <SelectionToolbar
          count={selected.length}
          onClear={() => setSelected([])}
          controls={columns.controls}
          selectAll={
            auth.can("TORRENT_SEND") &&
            search &&
            (result.data?.meta.totalItems ?? 0) > 0 && (
              <SelectAllRows<Update, number>
                url={`/torrent/revision-updates/search?${sortQuery(sort)}${registeredStatus ? `&status=${registeredStatus}` : ""}`}
                total={result.data?.meta.totalItems ?? 0}
                identify={(row) => row.eplId}
                onSelect={setSelected}
                loadPage={async (url) => {
                  const params = new URLSearchParams(url.split("?")[1]);
                  return savedPage(
                    Number(params.get("page")),
                    Number(params.get("size")),
                  );
                }}
              />
            )
          }
          actions={
            auth.can("TORRENT_SEND") && (
              <Button onClick={() => setSending(true)}>
                {t("revisionUpdates.send", { count: selected.length })}
              </Button>
            )
          }
        />
        {result.isFetching ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <>
            <div className="table-scroll">
              <table className="updates-table" style={{ width: columns.width }}>
                {columns.colgroup}
                <thead>
                  {columns.selectionHeadings(
                    auth.can("TORRENT_SEND") && (
                      <th className="updates-selection">
                        <Checkbox
                          aria-label={t("selection.page")}
                          checked={allChecked}
                          indeterminate={
                            !allChecked &&
                            rows.some((row) => selected.includes(row.eplId))
                          }
                          onChange={(e) => {
                            const checked = e.currentTarget.checked;
                            rows.forEach((row) => toggle(row.eplId, checked));
                          }}
                        />
                      </th>
                    ),
                  )}
                </thead>

                <tbody>
                  {!rows.length && (
                    <tr>
                      <td
                        colSpan={
                          columns.visible.length +
                          (auth.can("TORRENT_SEND") ? 1 : 0)
                        }
                      >
                        <p className="empty-list">
                          {t("revisionUpdates.empty")}
                        </p>
                      </td>
                    </tr>
                  )}
                  {rows.map((row) => (
                    <tr key={row.eplId}>
                      {auth.can("TORRENT_SEND") && (
                        <td className="updates-selection">
                          <Checkbox
                            aria-label={`EPL ${row.eplId}`}
                            checked={selected.includes(row.eplId)}
                            onChange={(e) =>
                              toggle(row.eplId, e.currentTarget.checked)
                            }
                          />
                        </td>
                      )}
                      {columns.cells([
                        <td>
                          <Link to={`/catalog/${row.eplId}`}>{row.eplId}</Link>
                        </td>,
                        <td className="catalog-title-cell">
                          <Link
                            className="book-title"
                            to={`/catalog/${row.eplId}`}
                          >
                            <BookCover book={row} />
                            <span>{row.title}</span>
                          </Link>
                        </td>,
                        <td>{status(row.status)}</td>,
                        <td>{revision(row.registeredRevision)}</td>,
                        <td>{revision(row.availableRevision)}</td>,
                      ])}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        <Paging
          choice={pageSizeChoice}
          meta={result.data?.meta}
          page={page}
          size={size}
          onPage={setPage}
          onSize={(value) => {
            setSize(value);
            setPage(0);
          }}
        />
      </section>
      <AppModal
        width="normal"
        opened={opened}
        onClose={() => {
          if (!find.isPending) setOpened(false);
        }}
        title={t("revisionUpdates.search")}
        icon={RefreshCw}
      >
        <p>{t("revisionUpdates.description")}</p>
        <fieldset className="send-options-group" disabled={find.isPending}>
          <legend>{t("updateSettings.states")}</legend>
          <div className="revision-search-state-grid">
            {downloadStates.map((value) => (
              <Checkbox
                key={value}
                label={status(value)}
                checked={options.includes(value)}
                onChange={(e) => {
                  const checked = e.currentTarget.checked;
                  setOptions((old) =>
                    checked ? [...old, value] : old.filter((s) => s !== value),
                  );
                  find.reset();
                }}
              />
            ))}
          </div>
        </fieldset>
        {find.isPending && (
          <p role="status">{t("revisionUpdates.searching")}</p>
        )}
        {find.isError && (
          <Failure error={find.error} retry={() => find.mutate()} />
        )}
        <ModalActions>
          <Button
            variant="default"
            disabled={find.isPending || !defaults.data}
            onClick={() => setOptions([...(defaults.data?.states ?? [])])}
          >
            {t("revisionUpdates.restore")}
          </Button>
          <Button
            variant="default"
            disabled={find.isPending}
            onClick={() => setOpened(false)}
          >
            {t("import.cancel")}
          </Button>
          <Button
            loading={find.isPending}
            disabled={!options.length}
            onClick={() => find.mutate()}
          >
            {t("catalog.search")}
          </Button>
        </ModalActions>
      </AppModal>
      {sending && (
        <SendSelection
          filters={{ eplId: selected }}
          revisionStates={search?.states}
          count={selected.length}
          allResults={false}
          onClose={() => {
            setSending(false);
            setSelected([]);
          }}
        />
      )}
    </>
  );
}
