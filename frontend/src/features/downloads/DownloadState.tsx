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
import { Button, Select, TextInput } from "@mantine/core";
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
  const { date, number, status } = useLocale();
  const cache = useQueryClient();
  const session = useSyncSession();
  const syncPending = useIsMutating({ mutationKey: ["torrent-sync"] }) > 0;
  const [filters, setFilters] = useState(initialFilters);
  const [draftEplId, setDraftEplId] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
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
  const summaryParams = new URLSearchParams(params);
  summaryParams.delete("sort");
  params.set("page", String(page));
  params.set("size", String(size));
  const result = useQuery({
    queryKey: ["downloads", params.toString()],
    queryFn: ({ signal }) =>
      get<Page<Download>>(`/torrent/downloads?${params}`, signal),
  });
  const summary = useQuery({
    queryKey: ["download-summary", summaryParams.toString()],
    queryFn: ({ signal }) =>
      get<{ total: number; byStatus: Record<string, number> }>(
        `/torrent/downloads/summary?${summaryParams}`,
        signal,
      ),
  });
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
      void cache.invalidateQueries({ queryKey: ["download-summary"] });
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
      { field: "lastCheckedAt", label: "downloads.lastChecked", width: 200 },
      { field: "completedAt", label: "downloads.completed", width: 200 },
      { field: "lastError", label: "downloads.error", width: 240 },
    ],
    filters.sort,
    (value) => filter("sort", value),
    {
      defaults: "createdAt,desc",
      extraSort: [
        { field: "createdAt", label: "downloads.createdAt", width: 200 },
      ],
    },
  );
  return (
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
      <form className="filters jobs-filters" onSubmit={(e) => e.preventDefault()}>
        <TextInput
          name="eplId"
          label={t("filters.eplId")}
          type="number"
          min={1}
          value={draftEplId}
          onChange={(e) => setDraftEplId(e.currentTarget.value)}
        />
        <Select
          label={t("downloads.status")}
          clearable
          value={filters.status || null}
          data={downloadStates.map((value) => ({
            value,
            label: status(value),
          }))}
          onChange={(v) => filter("status", v ?? "")}
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
      {summary.data && (
        <div className="status-summary">
          {Object.entries(summary.data.byStatus).map(([key, count]) => (
            <button
              key={key}
              className={filters.status === key ? "active" : ""}
              onClick={() =>
                filter("status", filters.status === key ? "" : key)
              }
            >
              {status(key)} <strong>{number(count)}</strong>
            </button>
          ))}
        </div>
      )}
      {summary.isError && (
        <Failure error={summary.error} retry={() => summary.refetch()} />
      )}
      <section className="panel">
        <div className="table-toolbar">
          <div className="catalog-table-controls">{columns.controls}</div>
        </div>
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <>
            <div className="table-scroll">
              <table
                className="download-record-table"
                style={{ width: columns.width }}
              >
                {columns.colgroup}
                <thead>{columns.headings}</thead>
                <tbody>
                  {result.data.items.map((d) => (
                    <tr key={d.id}>
                      {columns.cells([
                        <td>
                          <Link to={`/catalog/${d.eplId}`}>{d.eplId}</Link>
                        </td>,
                        <td className="catalog-title-cell">
                          <DownloadBook book={d} />
                        </td>,
                        <td className="hash-text">{d.hash}</td>,
                        <td>{number(d.revision)}</td>,
                        <td>
                          <span className="badge">{status(d.status)}</span>
                        </td>,
                        <td title={d.clientInstanceId}>
                          {d.client}
                          <small className="hash-text">{d.origin}</small>
                        </td>,
                        <td>{date(d.lastCheckedAt)}</td>,
                        <td>{date(d.completedAt)}</td>,
                        <td>{d.lastError || "—"}</td>,
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
        <Paging
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
  );
}
