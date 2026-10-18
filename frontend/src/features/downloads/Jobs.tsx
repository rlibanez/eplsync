import { WorkWaiting } from "../../components/WorkWaiting";
import { useRangeSelection } from "../../components/useRangeSelection";
import {
  pageSelection,
  togglePageSelection,
} from "../../components/pageSelection";
import { useJobItemSearch } from "../jobs/JobItemSearch";
import { useBookViewState } from "../catalog/useBookViewState";
import { BookLink } from "../catalog/BookLink";
import { useWorkDeletion } from "../jobs/useWorkDeletion";
import { stateOperatorProps } from "../../components/StateOperator";
import { WorkNavigation, useWorkContext } from "../jobs/WorkNavigation";
import { useJobRetry } from "../jobs/useJobRetry";
import { SelectionToolbar } from "../../components/SelectionToolbar";
import { PageTitle } from "../../components/PageTitle";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { dateBounds } from "../events/eventTypes";
import { sortQuery } from "./TableControls";
import { DownloadBook, useDownloadColumns } from "./DownloadTable";
import { useNotifications } from "../notifications/Notifications";
import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { CircleStop } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Checkbox,
  Button,
  MultiSelect,
  Progress,
  TextInput,
} from "@mantine/core";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import {
  Paging,
  post,
  jobStates,
  itemStates,
  type Job,
  type Page,
} from "./shared";
export function Jobs() {
  const { notify } = useNotifications();
  const { t } = useTranslation();
  const { date, status, number } = useLocale();
  const saved = useWorkContext("downloads")?.view;
  const [page, setPage] = useBookViewState("page", saved?.page ?? 0);
  const [size, setSize] = useBookViewState("size", saved?.size ?? 20);
  const pageSizeChoice = usePageSizeChoice(size);
  const [filter, setFilter] = useState(saved?.filter ?? "");
  const [excluded, setExcluded] = useBookViewState(
    "excluded",
    saved?.excluded ?? false,
  );
  const [excludeType, setExcludeType] = useState(saved?.excludeType ?? false);
  const [type, setType] = useState(saved?.type ?? "");
  const [username, setUsername] = useState(saved?.username ?? "");
  const [from, setFrom] = useState(saved?.from ?? "");
  const [to, setTo] = useState(saved?.to ?? "");
  const [sort, setSort] = useBookViewState(
    "sort",
    saved?.sort ?? "createdAt,desc",
  );
  const [refresh, setRefresh] = useState<
    "idle" | "pending" | "success" | "error"
  >("idle");
  const refreshRequest = useRef(0);
  useEffect(() => {
    refreshRequest.current++;
    setRefresh("idle");
    return () => {
      refreshRequest.current++;
    };
  }, [
    page,
    size,
    filter,
    excluded,
    excludeType,
    sort,
    type,
    username,
    from,
    to,
  ]);
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (filter) {
    params.set("status", filter);
    if (excluded) params.set("statusOperator", "ne");
  }
  if (type) {
    params.set("type", type);
    if (excludeType) params.set("typeOperator", "ne");
  }
  if (username.trim()) params.set("username", username.trim());
  const bounds = dateBounds(from, to);
  if (bounds.from) params.set("from", bounds.from);
  if (bounds.before) params.set("before", bounds.before);
  const result = useQuery({
    queryKey: ["jobs", params.toString(), sort],
    queryFn: ({ signal }) =>
      get<Page<Job>>(`/torrent/jobs?${params}&${sortQuery(sort)}`, signal),
    refetchInterval: 5000,
  });
  useEffect(() => {
    if (result.data)
      setPage((current) =>
        Math.min(current, Math.max(0, result.data.meta.totalPages - 1)),
      );
  }, [result.data]);
  const selectionScope = new URLSearchParams(params);
  selectionScope.delete("page");
  selectionScope.delete("size");
  const deletion = useWorkDeletion(
    "downloads",
    (result.data?.items ?? []).map((job) => ({
      id: job.jobId,
      cleanup: job.type === "CLEANUP",
    })),
    `${selectionScope}&${sort}`,
    `${page}:${size}`,
  );
  async function refreshJobs() {
    const request = ++refreshRequest.current;
    setRefresh("pending");
    try {
      const response = await result.refetch();
      if (request === refreshRequest.current) {
        setRefresh(response.isError ? "error" : "success");
        notify({
          title: t("nav.downloadJobs"),
          message: t(
            response.isError ? "downloads.refreshError" : "downloads.refreshed",
          ),
          tone: response.isError ? "error" : "success",
          href: "/downloads/jobs",
        });
      }
    } catch {
      if (request === refreshRequest.current) {
        setRefresh("error");
        notify({
          title: t("nav.downloadJobs"),
          message: t("downloads.refreshError"),
          tone: "error",
          href: "/downloads/jobs",
        });
      }
    }
  }
  const columns = useDownloadColumns(
    "eplsync.jobs.columns",
    [
      { field: "jobId", label: "downloads.job", width: 310 },
      { field: "createdAt", label: "downloads.createdAt", width: 200 },
      { field: "type", label: "downloads.type", width: 140 },
      { field: "status", label: "downloads.status", width: 160 },
      {
        field: "progress",
        label: "revisionCleanup.progressColumn",
        width: 180,
      },
      {
        field: "selectedBooks",
        label: "downloads.jobBooks",
        width: 160,
      },
      {
        field: "accepted",
        label: "revisionCleanup.acceptedOrRemoved",
        width: 160,
      },
      { field: "failed", label: "downloads.failedCount", width: 120 },

      { field: "username", label: "events.user", width: 180 },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
    },
    { defaults: "createdAt,desc", selection: deletion.visible },
    (page + 1) * size,
  );
  return (
    <>
      <div className="page-heading">
        <PageTitle
          title={t("nav.downloadJobs")}
          description={t("downloads.jobNote")}
        />
        <Button
          variant="filled"
          loading={refresh === "pending"}
          onClick={() => void refreshJobs()}
        >
          {t(
            refresh === "pending"
              ? "downloads.refreshing"
              : "downloads.refresh",
          )}
        </Button>
      </div>

      <div className="filters jobs-filters">
        <MultiSelect
          searchable
          styles={{ inputField: { flex: "1 1 40px", minWidth: 40 } }}
          label={t("downloads.type")}
          {...stateOperatorProps(excludeType, () => {
            setExcludeType(!excludeType);
            setPage(0);
          })}
          clearable
          value={type ? type.split(",") : []}
          data={["DOWNLOAD", "UPDATE", "CLEANUP"].map((value) => ({
            value,
            label: t(`historyActions.jobTypes.${value}`),
          }))}
          onChange={(value) => {
            setType(value.join(","));
            if (!value.length) setExcludeType(false);
            setPage(0);
          }}
        />
        <MultiSelect
          searchable
          styles={{ inputField: { flex: "1 1 40px", minWidth: 40 } }}
          label={t("downloads.status")}
          {...stateOperatorProps(excluded, () => {
            setExcluded(!excluded);
            setPage(0);
          })}
          clearable
          value={filter ? filter.split(",") : []}
          data={jobStates.map((value) => ({ value, label: status(value) }))}
          onChange={(v) => {
            setFilter(v.join(","));
            if (!v.length) setExcluded(false);
            setPage(0);
          }}
        />
        <TextInput
          label={t("events.user")}
          value={username}
          maxLength={64}
          onChange={(event) => {
            setUsername(event.currentTarget.value);
            setPage(0);
          }}
        />
        <TextInput
          type="date"
          label={t("filters.from")}
          value={from}
          max={to || undefined}
          onChange={(event) => {
            setFrom(event.currentTarget.value);
            setPage(0);
          }}
        />
        <TextInput
          type="date"
          label={t("filters.to")}
          value={to}
          min={from || undefined}
          onChange={(event) => {
            setTo(event.currentTarget.value);
            setPage(0);
          }}
        />
        <Button
          variant="subtle"
          onClick={() => {
            setFilter("");
            setExcluded(false);
            setType("");
            setExcludeType(false);
            setUsername("");
            setFrom("");
            setTo("");
            setPage(0);
          }}
        >
          {t("catalog.clear")}
        </Button>
      </div>
      <section className="panel">
        <SelectionToolbar
          count={deletion.count}
          onClear={deletion.clear}
          controls={columns.controls}
          actions={deletion.action}
          disabled={deletion.busy}
        />
        {deletion.content}
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure
            notFoundKey="downloads.jobNotFound"
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
                <thead>{columns.selectionHeadings(deletion.heading)}</thead>
                <tbody>
                  {result.data.items.map((job, rowIndex) => (
                    <tr key={job.jobId}>
                      {deletion.cell({
                        id: job.jobId,
                        cleanup: job.type === "CLEANUP",
                      })}
                      {columns.cells(
                        [
                          <td>
                            <Link
                              className="book-title"
                              state={{
                                workList: {
                                  source: "downloads",
                                  query: `${params}&${sortQuery(sort)}`,
                                  view: {
                                    page,
                                    size,
                                    filter,
                                    excluded,
                                    excludeType,
                                    type,
                                    username,
                                    from,
                                    to,
                                    sort,
                                  },
                                },
                              }}
                              to={
                                job.type === "CLEANUP"
                                  ? `/downloads/jobs/cleanup/${job.jobId}`
                                  : `/downloads/jobs/${job.jobId}`
                              }
                            >
                              {job.jobId}
                            </Link>
                          </td>,
                          <td>{date(job.createdAt)}</td>,
                          <td>
                            <button
                              type="button"
                              className="catalog-value-filter"
                              onClick={() => {
                                setType(job.type ?? "DOWNLOAD");
                                setExcludeType(false);
                                setPage(0);
                              }}
                            >
                              {t(
                                `historyActions.jobTypes.${job.type ?? "DOWNLOAD"}`,
                              )}
                            </button>
                          </td>,
                          <td>
                            <button
                              type="button"
                              className="catalog-value-filter"
                              onClick={() => {
                                setFilter(job.status);
                                setExcluded(false);
                                setPage(0);
                              }}
                            >
                              <span className="badge">
                                {status(job.status)}
                              </span>
                            </button>
                          </td>,
                          <td>
                            {number(job.processedItems)} /{" "}
                            {number(job.selectedItems)}
                          </td>,
                          <td>{number(job.selectedBooks)}</td>,
                          <td>{number(job.accepted)}</td>,
                          <td>{number(job.failed)}</td>,
                          <td>
                            {job.username ? (
                              <button
                                type="button"
                                className="catalog-value-filter"
                                onClick={() => {
                                  setUsername(job.username!);
                                  setPage(0);
                                }}
                              >
                                {job.username}
                              </button>
                            ) : (
                              "—"
                            )}
                          </td>,
                        ],
                        page * size + rowIndex + 1,
                      )}
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
  );
}
interface Item {
  revision?: number | null;
  title?: string | null;
  coverUrl?: string | null;
  coverAvailable?: boolean | null;
  id: string;
  eplId: number;
  hash: string;
  status: string;
  attempts: number;
  message: string | null;
}
export function JobDetail() {
  const { id } = useParams();
  const { t } = useTranslation();
  const { date, status, number, revision } = useLocale();
  const cache = useQueryClient();
  const [page, setPage] = useBookViewState("page", 0);
  const [size, setSize] = useBookViewState("size", 20);
  const pageSizeChoice = usePageSizeChoice(size);
  const [filter, setFilter] = useBookViewState("filter", "");
  const [excluded, setExcluded] = useBookViewState("excluded", false);
  const [sort, setSort] = useBookViewState("sort", "position,asc");
  const [cancel, setCancel] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const search = useJobItemSearch(
    "book",
    t("downloads.book"),
    () => setPage(0),
    t("calibreSend.detail"),
  );
  useEffect(() => setSelected([]), [id, filter, excluded, sort, search.query]);
  const job = useQuery({
    queryKey: ["job", id],
    queryFn: ({ signal }) =>
      get<Job>(`/torrent/jobs/${encodeURIComponent(id!)}`, signal),
    refetchInterval: (q) =>
      ["COMPLETED", "CANCELLED"].includes(q.state.data?.status ?? "") &&
      !(q.state.data?.inFlight ?? 0)
        ? q.state.data?.cleanup &&
          q.state.data.cleanup.waiting +
            q.state.data.cleanup.blocked +
            q.state.data.cleanup.requested >
            0
          ? 30000
          : false
        : 3000,
  });
  const items = useQuery({
    queryKey: [
      "job-items",
      id,
      page,
      size,
      filter,
      excluded,
      sort,
      search.query,
    ],
    queryFn: ({ signal }) =>
      get<Page<Item>>(
        `/torrent/jobs/${encodeURIComponent(id!)}/items?page=${page}&size=${size}&${sortQuery(sort)}&${search.query}${filter ? `&status=${filter}${excluded ? "&statusOperator=ne" : ""}` : ""}`,
        signal,
      ),
    refetchInterval:
      ["COMPLETED", "CANCELLED"].includes(job.data?.status ?? "") &&
      !(job.data?.inFlight ?? 0)
        ? false
        : 3000,
  });
  const retry = useJobRetry("downloads", id, job.data?.status, selected, () =>
    setSelected([]),
  );
  const control = useMutation({
    meta: {
      backendEvents: true,
      notice: { title: "downloads.job", href: `/downloads/jobs/${id}` },
    },
    mutationFn: (action: string) =>
      post<Job>(`/torrent/jobs/${encodeURIComponent(id!)}/${action}`),
    retry: false,
    onSuccess: (data) => {
      cache.setQueryData(["job", id], data);
      void cache.invalidateQueries({ queryKey: ["jobs"] });
      void cache.invalidateQueries({ queryKey: ["job-items", id] });
    },
  });
  const columns = useDownloadColumns(
    "eplsync.jobItems.columnWidths.v3",
    [
      { field: "eplId", label: "filters.eplId", width: 100 },
      { field: "title", label: "downloads.book", width: 360 },
      { field: "hash", label: "downloads.hash", width: 340 },
      { field: "revision", label: "downloads.revision", width: 110 },
      { field: "status", label: "downloads.status", width: 180 },
      { field: "attempts", label: "downloads.attempts", width: 120 },
      { field: "message", label: "downloads.message", width: 320 },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
    },
    {
      selection: retry.selectable,
      defaults: "position,asc",
      extraSort: [
        { field: "position", label: "tableControls.position", width: 100 },
      ],
    },
    (page + 1) * size,
  );
  const data = job.data;
  const selectRange = useRangeSelection(
    items.data?.items.map((row) => row.id) ?? [],
    (ids, checked) =>
      setSelected((current) => togglePageSelection(current, ids, checked)),
    `${id}:${page}:${size}:${sort}:${filter}:${excluded}:${search.query}`,
  );
  return (
    <>
      <WorkNavigation source="downloads" id={id} />
      <PageTitle
        title={t("downloads.jobHeading")}
        description={t("downloads.jobNote")}
      />
      <p className="hash-text">{id}</p>
      {retry.content}

      {job.isPending ? (
        <Loading />
      ) : job.isError ? (
        <Failure
          notFoundKey="downloads.jobNotFound"
          error={job.error}
          retry={() => job.refetch()}
        />
      ) : (
        data && (
          <section className="panel settings-section job-report-panel">
            <div className="job-controls-heading">
              <div className="action-row">
                {retry.failedAction}
                {["QUEUED", "RUNNING", "RETRY_WAIT", "CLEANUP_WAIT"].includes(
                  data.status,
                ) && (
                  <Button
                    disabled={control.isPending}
                    onClick={() => control.mutate("pause")}
                  >
                    {t("downloads.pause")}
                  </Button>
                )}
                {["PAUSED", "RETRY_WAIT"].includes(data.status) &&
                  !(
                    data.type === "UPDATE" &&
                    data.failed > 0 &&
                    data.pending === 0 &&
                    data.inFlight === 0
                  ) && (
                    <Button
                      disabled={control.isPending}
                      onClick={() => control.mutate("resume")}
                    >
                      {t("downloads.resume")}
                    </Button>
                  )}
                {!["COMPLETED", "CANCELLED"].includes(data.status) && (
                  <Button
                    color="red"
                    variant="light"
                    disabled={control.isPending}
                    onClick={() => setCancel(true)}
                  >
                    {t("downloads.cancelJob")}
                  </Button>
                )}
              </div>
            </div>
            <details className="report-collapse">
              <summary>
                <span>{t("downloads.report")}</span>
                <strong className="report-result">{status(data.status)}</strong>
              </summary>
              <div className="report-collapse-body">
                <Progress
                  aria-label={t("downloads.progress")}
                  value={
                    data.selectedItems
                      ? (100 * data.processedItems) / data.selectedItems
                      : 0
                  }
                />
                <div className="job-report-groups">
                  {[
                    {
                      title: "jobReport.work",
                      values: [
                        [
                          "downloads.type",
                          t(
                            `historyActions.jobTypes.${data.type ?? "DOWNLOAD"}`,
                          ),
                        ],
                        ["downloads.client", data.client],
                        ["downloads.createdAt", date(data.createdAt)],
                        ["downloads.updatedAt", date(data.updatedAt)],
                        ...(data.retryAt
                          ? [["downloads.retryAt", date(data.retryAt)]]
                          : []),
                      ],
                    },
                    {
                      title: "jobReport.submission",
                      values: [
                        [
                          "jobReport.phaseStatus",
                          status(
                            data.pending + data.inFlight === 0
                              ? data.cancelled > 0
                                ? "CANCELLED"
                                : "COMPLETED"
                              : data.status,
                          ),
                        ],
                        ...(
                          [
                            "selectedBooks",
                            "processedItems",
                            "selectedItems",
                            "accepted",
                            "alreadyExists",
                            "skipped",
                            "failed",
                            "pending",
                            "inFlight",
                            "cancelled",
                          ] as const
                        ).map((key) => [
                          `downloads.${key === "failed" ? "failedCount" : key}`,
                          number(data[key]),
                        ]),
                      ],
                    },
                    {
                      title: "send.options",
                      values: [
                        ["downloads.batchSize", number(data.batchSize)],
                        ["downloads.concurrency", number(data.concurrency)],
                        ["send.interval", data.interval],
                        [
                          "send.multipleHashes",
                          data.multipleHashes
                            ? t(`send.${data.multipleHashes.toLowerCase()}`)
                            : "—",
                        ],
                      ],
                    },
                    ...(data.previousVersions || data.cleanup
                      ? [
                          {
                            title: "jobReport.cleanup",
                            values: [
                              [
                                "jobReport.phaseStatus",
                                data.status === "PAUSED"
                                  ? status("PAUSED")
                                  : data.cleanup?.blocked
                                    ? t("jobReport.cleanupBlocked")
                                    : data.cleanup &&
                                        data.cleanup.waiting +
                                          data.cleanup.requested >
                                          0
                                      ? status("CLEANUP_WAIT")
                                      : data.cleanup?.cancelled
                                        ? status("CANCELLED")
                                        : data.previousVersions === "keep" &&
                                            !data.deleteHistory
                                          ? t("jobReport.notRequired")
                                          : status("COMPLETED"),
                              ],
                              ...(data.previousVersions
                                ? [
                                    [
                                      "send.previousAction",
                                      t(
                                        `historyActions.policies.${data.previousVersions}`,
                                      ),
                                    ],
                                  ]
                                : []),
                              ...(data.previousVersions &&
                              (data.previousVersions !== "keep" ||
                                data.deleteHistory)
                                ? [
                                    [
                                      "send.cleanupTiming",
                                      t(
                                        data.cleanupTiming === "immediate"
                                          ? "send.cleanupImmediate"
                                          : "send.cleanupAfterDownload",
                                      ),
                                    ],
                                  ]
                                : []),
                              ...(data.previousVersions
                                ? [
                                    [
                                      "send.deleteHistory",
                                      t(
                                        data.deleteHistory
                                          ? "downloads.yes"
                                          : "downloads.no",
                                      ),
                                    ],
                                  ]
                                : []),
                              ...Object.entries(data.cleanup ?? {}).map(
                                ([key, value]) => [
                                  `historyActions.summary.${key}`,
                                  number(value),
                                ],
                              ),
                            ],
                          },
                        ]
                      : []),
                  ].map((group) => (
                    <section className="job-report-group" key={group.title}>
                      <h3>{t(group.title)}</h3>
                      <dl className="import-summary">
                        {group.values.map(([label, value]) => (
                          <div key={label}>
                            <dt>{t(label)}</dt>
                            <dd>{value}</dd>
                          </div>
                        ))}
                      </dl>
                    </section>
                  ))}
                </div>
                {data.message && <Alert>{data.message}</Alert>}
              </div>
            </details>
          </section>
        )
      )}
      <div className="filters jobs-filters">
        {search.fields}
        <MultiSelect
          searchable
          label={t("downloads.itemStatus")}
          clearButtonProps={{
            "aria-hidden": false,
            tabIndex: 0,
            "aria-label": `${t("catalog.clear")}: ${t("downloads.itemStatus")}`,
          }}
          {...stateOperatorProps(excluded, () => {
            setExcluded(!excluded);
            setPage(0);
          })}
          value={filter ? filter.split(",") : []}
          clearable
          data={itemStates.map((value) => ({ value, label: status(value) }))}
          onChange={(v) => {
            setFilter(v.join(","));
            if (!v.length) setExcluded(false);
            setPage(0);
          }}
        />
        <Button
          variant="subtle"
          onClick={() => {
            search.clear();
            setFilter("");
            setExcluded(false);
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
          actions={retry.action}
        />
        {items.isPending ? (
          <Loading />
        ) : items.isError ? (
          <Failure
            notFoundKey="downloads.jobNotFound"
            error={items.error}
            retry={() => items.refetch()}
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
                    retry.selectable && (
                      <th>
                        <Checkbox
                          aria-label={t("selection.page")}
                          {...pageSelection(
                            selected,
                            (items.data?.items ?? []).map((row) => row.id),
                          )}
                          onChange={(e) => {
                            const checked = e.currentTarget.checked;
                            const ids = (items.data?.items ?? []).map(
                              (row) => row.id,
                            );
                            setSelected((current) =>
                              togglePageSelection(current, ids, checked),
                            );
                          }}
                        />
                      </th>
                    ),
                  )}
                </thead>
                <tbody>
                  {items.data.items.map((item, rowIndex) => (
                    <tr key={item.id}>
                      {retry.selectable && (
                        <td>
                          <Checkbox
                            aria-label={`EPL ${item.eplId}`}
                            checked={selected.includes(item.id)}
                            onMouseDown={selectRange.preventTextSelection}
                            onChange={(e) => selectRange(item.id, e)}
                          />
                        </td>
                      )}
                      {columns.cells(
                        [
                          <td>
                            <BookLink to={`/catalog/${item.eplId}`}>
                              {item.eplId}
                            </BookLink>
                          </td>,
                          <td className="catalog-title-cell">
                            <DownloadBook book={item} />
                          </td>,
                          <td className="hash-text">{item.hash}</td>,
                          <td>
                            {item.revision == null
                              ? "—"
                              : revision(item.revision)}
                          </td>,
                          <td>
                            <button
                              type="button"
                              className="catalog-value-filter"
                              onClick={() => {
                                setFilter(item.status);
                                setExcluded(false);
                                setPage(0);
                              }}
                            >
                              {status(item.status)}
                            </button>
                          </td>,
                          <td>{item.attempts}</td>,
                          <td>
                            {item.message || "—"}
                            <WorkWaiting
                              state={item.status}
                              jobState={data?.status}
                            />
                          </td>,
                        ],
                        page * size + rowIndex + 1,
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!items.data.items.length && (
              <p className="empty-list">{t("downloads.empty")}</p>
            )}
          </>
        )}
        <Paging
          choice={pageSizeChoice}
          meta={items.data?.meta}
          page={page}
          size={size}
          onPage={setPage}
          onSize={(n) => {
            setSize(n);
            setPage(0);
          }}
        />
      </section>
      <Modal
        width="compact"
        icon={CircleStop}
        opened={cancel}
        onClose={() => setCancel(false)}
        title={t("downloads.cancelJob")}
        centered
      >
        <p>{t("downloads.cancelConfirm")}</p>
        <ModalActions>
          <Button variant="default" onClick={() => setCancel(false)}>
            {t("common.cancel")}
          </Button>
          <Button
            color="red"
            onClick={() => {
              setCancel(false);
              control.mutate("cancel");
            }}
          >
            {t("downloads.cancelJob")}
          </Button>
        </ModalActions>
      </Modal>
    </>
  );
}
