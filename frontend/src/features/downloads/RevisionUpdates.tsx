import { useUpdateSession } from "./useUpdateSession";
import { BookCover } from "../catalog/BookCover";
import { useDownloadColumns } from "./DownloadTable";
import { sortQuery } from "./TableControls";
import { useEffect, useState } from "react";
import { Button, Checkbox, Select } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { RefreshCw } from "lucide-react";
import { useAuth } from "../auth/Auth";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, post, downloadStates, type Page } from "./shared";
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
  const { status, number, date } = useLocale();
  const auth = useAuth();
  const cache = useQueryClient();
  const [opened, setOpened] = useState(false);
  const [options, setOptions] = useState<string[]>([]);
  const session = useUpdateSession();
  const { search, page, size, sort, registeredStatus } = session;
  const setPage = (page: number) => session.setView({ page });
  const setSize = (size: number) => session.setView({ size });
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
    queryFn: () =>
      post<Page<Update>>(
        `/torrent/revision-updates/search?page=${page}&size=${size}&${sortQuery(sort)}${registeredStatus ? `&status=${registeredStatus}` : ""}`,
        { states: search!.states },
      ),
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
      post<Page<Update>>(
        `/torrent/revision-updates/search?synchronize=true&page=0&size=${size}&${sortQuery(sort)}`,
        { states: options },
      ),
    retry: false,
    onSuccess: (data) => {
      const generation = Date.now();
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
      { field: "status", label: "revisionUpdates.status", width: 200 },
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
        <h1>{t("nav.updates")}</h1>
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
      {!search && <p className="muted">{t("revisionUpdates.start")}</p>}
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
        <Select
          label={t("revisionUpdates.status")}
          clearable
          value={registeredStatus}
          disabled={!search || find.isPending}
          data={downloadStates.map((value) => ({
            value,
            label: status(value),
          }))}
          onChange={(value) => {
            setRegisteredStatus(value);
            setPage(0);
            setSelected([]);
          }}
        />
      </div>
      {search && (
        <section className="panel">
          <div className="table-toolbar">
            <span>
              {t("revisionUpdates.count", {
                count: result.data?.meta.totalItems ?? 0,
              })}
            </span>
            <div className="catalog-table-controls">{columns.controls}</div>
          </div>
          {result.isFetching ? (
            <Loading />
          ) : result.isError ? (
            <Failure error={result.error} retry={() => result.refetch()} />
          ) : (
            <>
              {auth.can("TORRENT_SEND") && selected.length > 0 && (
                <div className="catalog-selection-bar has-selection">
                  <div className="catalog-selection-info">
                    <span
                      className="catalog-selection-count"
                      aria-live="polite"
                    >
                      {t("selection.selected", { count: selected.length })}
                    </span>
                  </div>
                  <div className="catalog-selection-actions">
                    <Button onClick={() => setSending(true)}>
                      {t("revisionUpdates.send", { count: selected.length })}
                    </Button>
                  </div>
                </div>
              )}
              <div className="table-scroll">
                <table
                  className="updates-table"
                  style={{ width: columns.width }}
                >
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
                            <Link to={`/catalog/${row.eplId}`}>
                              {row.eplId}
                            </Link>
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
                          <td>{number(row.registeredRevision)}</td>,
                          <td>{number(row.availableRevision)}</td>,
                          <td>{status(row.status)}</td>,
                        ])}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!rows.length && (
                <p className="empty-list">{t("revisionUpdates.empty")}</p>
              )}
            </>
          )}
          <Paging
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
      )}
      <AppModal
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
          <div className="settings-fields-grid">
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
            {t("revisionUpdates.search")}
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
