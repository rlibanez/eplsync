import { SelectAllRows } from "../../components/SelectAllRows";
import { CalibreSendProvider, SendToCalibre } from "../calibre/SendToCalibre";
import { DownloadActions } from "./DownloadActions";
import { TableAction } from "../../components/TableAction";
import { RefreshCw, Trash2 } from "lucide-react";
import { EpubBatchDownload } from "./EpubBatchDownload";
import { EpubDownload } from "./EpubDownload";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { ordering } from "./TableControls";
import { DownloadBook, useDownloadColumns } from "./DownloadTable";
import { useAuth } from "../auth/Auth";
import { useSyncSession } from "./useSyncSession";
import { SyncReport, type SyncResult } from "./SyncReport";
import { useEffect, useState } from "react";
import {
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  Button,
  Checkbox,
  Group,
  Select,
  MultiSelect,
  TextInput,
} from "@mantine/core";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, post, downloadStates, type Page } from "./shared";
export interface Download {
  id: string;
  eplId: number;
  title?: string | null;
  coverUrl?: string | null;
  coverAvailable?: boolean | null;
  revision: number;
  hash: string;
  client: string;
  clientInstanceId: string;
  origin: string;
  status: string;
  createdAt: string;
  completedAt: string | null;
  lastCheckedAt: string | null;
  lastError: string | null;
}
const initialFilters = {
  eplId: "",
  status: "",
  origin: "",
  completed: "",
  sort: "createdAt,desc",
};

export function DownloadState() {
  return <DownloadHistory />;
}
function DownloadHistory() {
  const { t } = useTranslation();
  const auth = useAuth();
  const { date, status, revision } = useLocale();
  const cache = useQueryClient();
  const session = useSyncSession();
  const syncPending = useIsMutating({ mutationKey: ["torrent-sync"] }) > 0;
  const [filters, setFilters] = useState(initialFilters);
  const [draftEplId, setDraftEplId] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const pageSizeChoice = usePageSizeChoice(size);
  const selectable =
    (auth.can("CALIBRE_SEND") && auth.can("DOWNLOADS_FILES_READ")) ||
    auth.can("DOWNLOADS_FILES_READ") ||
    auth.can("TORRENT_SYNC") ||
    auth.can("DOWNLOADS_DELETE") ||
    auth.can("TORRENT_CLEANUP");
  const [selected, setSelected] = useState<string[]>([]);
  useEffect(() => setSelected([]), [filters]);
  useEffect(() => {
    if (draftEplId === filters.eplId) return;
    if (draftEplId !== "" && !/^[1-9]\d*$/.test(draftEplId)) return;
    const timer = window.setTimeout(() => {
      setFilters((current) => ({ ...current, eplId: draftEplId }));
      setPage(0);
    }, 400);
    return () => window.clearTimeout(timer);
  }, [draftEplId, filters.eplId]);

  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters))
    if (value) {
      if (key === "sort")
        ordering(value).forEach((sort) => params.append("sort", sort));
      else params.set(key, value);
    }
  params.set("page", String(page));
  params.set("size", String(size));
  const result = useQuery({
    queryKey: ["downloads", params.toString()],
    queryFn: ({ signal }) =>
      get<Page<Download>>(`/torrent/downloads?${params}`, signal),
  });
  useEffect(() => {
    if (result.data) {
      setPage((current) =>
        Math.min(current, Math.max(0, result.data.meta.totalPages - 1)),
      );
    }
  }, [result.data]);
  const sync = useMutation({
    mutationKey: ["torrent-sync"],
    meta: {
      backendEvents: true,
      notice: {
        title: "downloads.sync",
        success: "notifications.operationDone",
        href: "/downloads",
      },
    },
    mutationFn: (dryRun: boolean) =>
      post<SyncResult>("/torrent/downloads/sync", {
        dryRun,
        includeDetails: true,
      }),
    retry: false,
    onSuccess: (report) => {
      session.save(report);
      if (!report.applied) return;
      void cache.invalidateQueries({ queryKey: ["catalog"] });
      void cache.invalidateQueries({ queryKey: ["downloads"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
      void cache.invalidateQueries({ queryKey: ["book-history"] });
    },
  });
  function filter(key: string, value: string) {
    setFilters((f) => ({ ...f, [key]: value }));
    setPage(0);
  }
  const columns = useDownloadColumns(
    "eplsync.downloads.columnWidths",
    [
      { field: "eplId", label: "filters.eplId", width: 100 },
      { field: "title", label: "downloads.book", width: 360 },
      { field: "hash", label: "downloads.hash", width: 340 },
      { field: "revision", label: "downloads.revision", width: 110 },
      { field: "status", label: "downloads.status", width: 160 },
      { field: "client", label: "downloads.client", width: 180 },
      { field: "origin", label: "downloads.origin", width: 180 },
      { field: "lastCheckedAt", label: "downloads.lastChecked", width: 200 },
      { field: "completedAt", label: "downloads.completed", width: 200 },
      { field: "lastError", label: "downloads.error", width: 240 },
      {
        field: "actions",
        label: "historyActions.actions",
        width: 150,
        sortable: false,
      },
    ],
    filters.sort,
    (value) => filter("sort", value),
    {
      defaults: "createdAt,desc",
      selection: selectable,
      hidden: selectable ? [] : ["actions"],
      extraSort: [
        { field: "createdAt", label: "downloads.createdAt", width: 200 },
      ],
    },
  );
  return (
    <CalibreSendProvider>
      <>
        <div className="page-heading">
          <h1>{t("downloads.stateTitle")}</h1>
          {auth.can("TORRENT_SYNC") && (
            <div className="action-row">
              <Button
                variant="default"
                disabled={syncPending}
                loading={sync.isPending && sync.variables === true}
                onClick={() => sync.mutate(true)}
              >
                {t("syncReport.previewAction")}
              </Button>
              <Button
                disabled={syncPending}
                loading={sync.isPending && sync.variables === false}
                onClick={() => sync.mutate(false)}
              >
                {t("downloads.sync")}
              </Button>
            </div>
          )}
        </div>
        <p className="muted">{t("downloads.localNote")}</p>
        {session.report && (
          <SyncReport report={session.report} onClose={session.close} />
        )}
        <form
          className="filters jobs-filters"
          onSubmit={(e) => e.preventDefault()}
        >
          <TextInput
            name="eplId"
            label={t("filters.eplId")}
            type="number"
            min={1}
            value={draftEplId}
            onChange={(e) => setDraftEplId(e.currentTarget.value)}
          />
          <MultiSelect
            searchable
            styles={{ inputField: { flex: "1 1 40px", minWidth: 40 } }}
            label={t("downloads.status")}
            clearable
            value={filters.status ? filters.status.split(",") : []}
            data={downloadStates.map((value) => ({
              value,
              label: status(value),
            }))}
            onChange={(values) => filter("status", values.join(","))}
          />
          <Select
            label={t("downloads.origin")}
            value={filters.origin || null}
            clearable
            data={[
              { value: "EPLSYNC", label: "EPL Sync" },
              { value: "DISCOVERED", label: t("downloads.discovered") },
            ]}
            onChange={(v) => filter("origin", v ?? "")}
          />
          <Select
            label={t("downloads.completed")}
            value={filters.completed || null}
            clearable
            data={[
              { value: "true", label: t("downloads.yes") },
              { value: "false", label: t("downloads.no") },
            ]}
            onChange={(v) => filter("completed", v ?? "")}
          />
          <Button
            type="button"
            variant="subtle"
            onClick={() => {
              setDraftEplId("");
              setFilters(initialFilters);
              setPage(0);
              setSize(20);
            }}
          >
            {t("catalog.clear")}
          </Button>
        </form>
        <section className="panel">
          <div className="table-toolbar">
            {selectable && (
              <SelectAllRows<Download, string>
                url={`/torrent/downloads?${new URLSearchParams([...params].filter(([key]) => key !== "page" && key !== "size"))}`}
                total={result.data?.meta.totalItems ?? 0}
                identify={(row) => row.id}
                onSelect={setSelected}
              />
            )}
            <div className="catalog-table-controls">{columns.controls}</div>
          </div>
          <DownloadActions
            selected={selected}
            rows={result.data?.items ?? []}
            onDone={(ids) =>
              setSelected((previous) =>
                previous.filter((id) => !ids.includes(id)),
              )
            }
            extraActions={
              <>
                <SendToCalibre ids={selected} />
                {auth.can("DOWNLOADS_FILES_READ") && (
                  <EpubBatchDownload selected={selected} toolbar={false} />
                )}
              </>
            }
          >
            {({ pending, refreshingIds, refreshRow, removeRow }) => (
              <>
                {result.isPending ? (
                  <Loading />
                ) : result.isError ? (
                  <Failure
                    error={result.error}
                    retry={() => result.refetch()}
                  />
                ) : (
                  <>
                    <div className="table-scroll">
                      <table
                        className="download-record-table"
                        style={{ width: columns.width }}
                      >
                        {columns.colgroup}
                        <thead>
                          {columns.selectionHeadings(
                            selectable && (
                              <th className="history-selection">
                                <Checkbox
                                  aria-label={t("historyActions.selectPage")}
                                  checked={
                                    result.data.items.length > 0 &&
                                    result.data.items.every((row) =>
                                      selected.includes(row.id),
                                    )
                                  }
                                  indeterminate={
                                    selected.length > 0 &&
                                    !result.data.items.every((row) =>
                                      selected.includes(row.id),
                                    )
                                  }
                                  disabled={!result.data.items.length}
                                  onChange={(event) =>
                                    setSelected(
                                      event.currentTarget.checked
                                        ? result.data.items.map((row) => row.id)
                                        : [],
                                    )
                                  }
                                />
                              </th>
                            ),
                          )}
                        </thead>
                        <tbody>
                          {result.data.items.map((d) => (
                            <tr key={d.id}>
                              {selectable && (
                                <td className="history-selection">
                                  <Checkbox
                                    aria-label={t("epubDownload.selectRecord", {
                                      id: d.eplId,
                                      revision: revision(d.revision),
                                    })}
                                    checked={selected.includes(d.id)}
                                    onChange={(event) => {
                                      const checked =
                                        event.currentTarget.checked;
                                      setSelected((previous) =>
                                        checked
                                          ? [...previous, d.id]
                                          : previous.filter(
                                              (id) => id !== d.id,
                                            ),
                                      );
                                    }}
                                  />
                                </td>
                              )}
                              {columns.cells([
                                <td>
                                  <Link to={`/catalog/${d.eplId}`}>
                                    {d.eplId}
                                  </Link>
                                </td>,
                                <td className="catalog-title-cell">
                                  <DownloadBook book={d} />
                                </td>,
                                <td>
                                  <small className="hash-text">{d.hash}</small>
                                </td>,
                                <td>{revision(d.revision)}</td>,
                                <td>
                                  <span className="badge">
                                    {status(d.status)}
                                  </span>
                                </td>,
                                <td title={d.clientInstanceId}>{d.client}</td>,
                                <td>{d.origin}</td>,
                                <td>{date(d.lastCheckedAt)}</td>,
                                <td>{date(d.completedAt)}</td>,
                                <td>{d.lastError || "—"}</td>,
                                <td>
                                  <Group gap={4} wrap="nowrap">
                                    <EpubDownload id={d.id} />
                                    <SendToCalibre ids={[d.id]} inline />
                                    {auth.can("TORRENT_SYNC") && (
                                      <TableAction
                                        label={t("historyActions.refresh")}
                                        disabled={pending}
                                        loading={refreshingIds.includes(d.id)}
                                        onClick={() => refreshRow(d)}
                                      >
                                        <RefreshCw
                                          size={16}
                                          aria-hidden="true"
                                        />
                                      </TableAction>
                                    )}
                                    {(auth.can("DOWNLOADS_DELETE") ||
                                      auth.can("TORRENT_CLEANUP")) && (
                                      <TableAction
                                        label={t("historyActions.delete")}
                                        disabled={pending}
                                        onClick={() => removeRow(d)}
                                      >
                                        <Trash2 size={16} aria-hidden="true" />
                                      </TableAction>
                                    )}
                                  </Group>
                                </td>,
                              ])}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {!result.data.items.length && (
                      <p className="empty-list">{t("downloads.empty")}</p>
                    )}
                  </>
                )}
              </>
            )}
          </DownloadActions>
          <Paging
            choice={pageSizeChoice}
            meta={result.data?.meta}
            page={page}
            size={size}
            onPage={setPage}
            onSize={(n) => {
              setSize(n);
              setPage(0);
            }}
          />
        </section>
      </>
    </CalibreSendProvider>
  );
}
