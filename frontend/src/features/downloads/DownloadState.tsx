import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Select, TextInput, Alert } from "@mantine/core";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import {
  ActionFailure,
  Paging,
  post,
  downloadStates,
  type Page,
} from "./shared";
interface Download {
  id: string;
  eplId: number;
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
interface Sync {
  checkedAt: string;
  remoteTorrents: number;
  checked: number;
  created: number;
  updated: number;
  completed: number;
  notFound: number;
  ignored: number;
}
export function DownloadState() {
  const { t } = useTranslation();
  const { date, number, status } = useLocale();
  const cache = useQueryClient();
  const [filters, setFilters] = useState({
    eplId: "",
    status: "",
    origin: "",
    completed: "",
    sort: "createdAt,desc",
  });
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters))
    if (value) params.set(key, value);
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
    mutationFn: () => post<Sync>("/torrent/downloads/sync"),
    retry: false,
    onSuccess: () => {
      void cache.invalidateQueries({ queryKey: ["downloads"] });
      void cache.invalidateQueries({ queryKey: ["download-summary"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
    },
  });
  function filter(key: string, value: string) {
    setFilters((f) => ({ ...f, [key]: value }));
    setPage(0);
  }
  return (
    <>
      <div className="page-heading">
        <h1>{t("downloads.stateTitle")}</h1>
        <Button loading={sync.isPending} onClick={() => sync.mutate()}>
          {t("downloads.sync")}
        </Button>
      </div>
      <p className="muted">{t("downloads.localNote")}</p>
      <ActionFailure error={sync.error} />
      {sync.data && (
        <Alert color="green">
          {t("downloads.synced", { date: date(sync.data.checkedAt) })}
          <details>
            <summary>{t("downloads.syncDetails")}</summary>
            <dl className="import-summary">
              {(
                [
                  "remoteTorrents",
                  "checked",
                  "created",
                  "updated",
                  "completed",
                  "notFound",
                  "ignored",
                ] as const
              ).map((key) => (
                <div key={key}>
                  <dt>{t(`downloads.syncCounts.${key}`)}</dt>
                  <dd>{number(sync.data![key])}</dd>
                </div>
              ))}
            </dl>
          </details>
        </Alert>
      )}
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          const data = new FormData(e.currentTarget);
          filter("eplId", String(data.get("eplId") ?? ""));
        }}
      >
        <TextInput name="eplId" label="EPL ID" type="number" min={1} />
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
          clearable
          data={[
            { value: "EPLSYNC", label: "EPL Sync" },
            { value: "DISCOVERED", label: t("downloads.discovered") },
          ]}
          onChange={(v) => filter("origin", v ?? "")}
        />
        <Select
          label={t("downloads.completed")}
          clearable
          data={[
            { value: "true", label: t("downloads.yes") },
            { value: "false", label: t("downloads.no") },
          ]}
          onChange={(v) => filter("completed", v ?? "")}
        />
        <Button type="submit">{t("catalog.search")}</Button>
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
          <span>{t("downloads.recordsNote")}</span>
          <Select
            aria-label={t("downloads.sort")}
            value={filters.sort}
            data={[
              { value: "createdAt,desc", label: t("downloads.newest") },
              {
                value: "lastCheckedAt,desc",
                label: t("downloads.lastChecked"),
              },
              { value: "eplId,asc", label: "EPL ID" },
            ]}
            onChange={(v) => filter("sort", v ?? "createdAt,desc")}
          />
        </div>
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    {[
                      "book",
                      "revision",
                      "status",
                      "client",
                      "lastChecked",
                      "completed",
                      "error",
                    ].map((key) => (
                      <th key={key}>{t(`downloads.${key}`)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.data.items.map((d) => (
                    <tr key={d.id}>
                      <td>
                        <Link className="book-title" to={`/catalog/${d.eplId}`}>
                          EPL {d.eplId}
                        </Link>
                        <small className="hash-text" title={d.hash}>
                          {d.hash}
                        </small>
                      </td>
                      <td>{number(d.revision)}</td>
                      <td>
                        <span className="badge">{status(d.status)}</span>
                      </td>
                      <td title={d.clientInstanceId}>
                        {d.client}
                        <small className="hash-text">{d.origin}</small>
                      </td>
                      <td>{date(d.lastCheckedAt)}</td>
                      <td>{date(d.completedAt)}</td>
                      <td>{d.lastError || "—"}</td>
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
