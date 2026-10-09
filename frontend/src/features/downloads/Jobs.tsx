import { dateBounds } from "../events/eventTypes";
import { sortQuery } from "./TableControls";
import { DownloadBook, useDownloadColumns } from "./DownloadTable";
import { useNotifications } from "../notifications/Notifications";
import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { CircleStop } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Select, Progress, TextInput } from "@mantine/core";
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
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [filter, setFilter] = useState("");
  const [type, setType] = useState("");
  const [username, setUsername] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [sort, setSort] = useState("createdAt,desc");
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
  }, [page, size, filter, sort, type, username, from, to]);
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (filter) params.set("status", filter);
  if (type) params.set("type", type);
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
  async function refreshJobs() {
    const request = ++refreshRequest.current;
    setRefresh("pending");
    try {
      const response = await result.refetch();
      if (request === refreshRequest.current) {
        setRefresh(response.isError ? "error" : "success");
        notify({
          title: t("nav.jobs"),
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
          title: t("nav.jobs"),
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
      { field: "type", label: "downloads.type", width: 140 },
      { field: "status", label: "downloads.status", width: 160 },
      { field: "username", label: "events.user", width: 180 },
      { field: "progress", label: "downloads.progress", width: 180 },
      {
        field: "selectedBooks",
        label: "downloads.selectedBooks",
        width: 160,
      },
      { field: "accepted", label: "downloads.accepted", width: 120 },
      { field: "failed", label: "downloads.failedCount", width: 120 },
      { field: "createdAt", label: "downloads.createdAt", width: 200 },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
    },
    { defaults: "createdAt,desc" },
  );
  return (
    <>
      <div className="page-heading">
        <h1>{t("nav.jobs")}</h1>
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

      <p className="muted">{t("downloads.jobNote")}</p>
      <div className="filters jobs-filters">
        <Select
          label={t("downloads.type")}
          clearable
          value={type || null}
          data={["DOWNLOAD", "UPDATE"].map((value) => ({
            value,
            label: t(`historyActions.jobTypes.${value}`),
          }))}
          onChange={(value) => {
            setType(value ?? "");
            setPage(0);
          }}
        />
        <Select
          label={t("downloads.status")}
          clearable
          value={filter || null}
          data={jobStates.map((value) => ({ value, label: status(value) }))}
          onChange={(v) => {
            setFilter(v ?? "");
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
            setType("");
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
        <div className="table-toolbar">
          <div className="catalog-table-controls">{columns.controls}</div>
        </div>
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
                <thead>{columns.headings}</thead>
                <tbody>
                  {result.data.items.map((job) => (
                    <tr key={job.jobId}>
                      {columns.cells([
                        <td>
                          <Link
                            className="book-title"
                            to={`/downloads/jobs/${job.jobId}`}
                          >
                            {job.jobId}
                          </Link>
                        </td>,
                        <td>
                          {t(
                            `historyActions.jobTypes.${job.type ?? "DOWNLOAD"}`,
                          )}
                        </td>,
                        <td>{status(job.status)}</td>,
                        <td>{job.username || "—"}</td>,
                        <td>
                          {number(job.processedItems)} /{" "}
                          {number(job.selectedItems)}
                        </td>,
                        <td>{number(job.selectedBooks)}</td>,
                        <td>{number(job.accepted)}</td>,
                        <td>{number(job.failed)}</td>,
                        <td>{date(job.createdAt)}</td>,
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
  const { date, status, number } = useLocale();
  const cache = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [filter, setFilter] = useState("");
  const [sort, setSort] = useState("position,asc");
  const [cancel, setCancel] = useState(false);
  const job = useQuery({
    queryKey: ["job", id],
    queryFn: ({ signal }) =>
      get<Job>(`/torrent/jobs/${encodeURIComponent(id!)}`, signal),
    refetchInterval: (q) =>
      ["COMPLETED", "CANCELLED"].includes(q.state.data?.status ?? "")
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
    queryKey: ["job-items", id, page, size, filter, sort],
    queryFn: ({ signal }) =>
      get<Page<Item>>(
        `/torrent/jobs/${encodeURIComponent(id!)}/items?page=${page}&size=${size}&${sortQuery(sort)}${filter ? `&status=${filter}` : ""}`,
        signal,
      ),
    refetchInterval: ["COMPLETED", "CANCELLED"].includes(job.data?.status ?? "")
      ? false
      : 3000,
  });
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
      defaults: "position,asc",
      extraSort: [
        { field: "position", label: "tableControls.position", width: 100 },
      ],
    },
  );
  const data = job.data;
  return (
    <>
      <Link className="back-link" to="/downloads/jobs">
        ← {t("nav.jobs")}
      </Link>
      <h1>{t("downloads.job")}</h1>
      <p className="hash-text">{id}</p>
      <p className="muted">{t("downloads.jobNote")}</p>
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
                {["QUEUED", "RUNNING", "RETRY_WAIT"].includes(data.status) && (
                  <Button
                    disabled={control.isPending}
                    onClick={() => control.mutate("pause")}
                  >
                    {t("downloads.pause")}
                  </Button>
                )}
                {["PAUSED", "RETRY_WAIT"].includes(data.status) && (
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
                      title: "downloads.job",
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
                      title: "downloads.progress",
                      values: (
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
                            title: "send.previousVersions",
                            values: [
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
                              data.previousVersions !== "keep"
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
        <Select
          label={t("downloads.itemStatus")}
          value={filter || null}
          clearable
          data={itemStates.map((value) => ({ value, label: status(value) }))}
          onChange={(v) => {
            setFilter(v ?? "");
            setPage(0);
          }}
        />
      </div>
      <section className="panel">
        <div className="table-toolbar">
          <div className="catalog-table-controls">{columns.controls}</div>
        </div>
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
                <thead>{columns.headings}</thead>
                <tbody>
                  {items.data.items.map((item) => (
                    <tr key={item.id}>
                      {columns.cells([
                        <td>
                          <Link to={`/catalog/${item.eplId}`}>
                            {item.eplId}
                          </Link>
                        </td>,
                        <td className="catalog-title-cell">
                          <DownloadBook book={item} />
                        </td>,
                        <td className="hash-text">{item.hash}</td>,
                        <td>
                          {item.revision == null ? "—" : number(item.revision)}
                        </td>,
                        <td>{status(item.status)}</td>,
                        <td>{item.attempts}</td>,
                        <td>{item.message || "—"}</td>,
                      ])}
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
        icon={CircleStop}
        opened={cancel}
        onClose={() => setCancel(false)}
        title={t("downloads.cancelJob")}
        centered
      >
        <p>{t("downloads.cancelConfirm")}</p>
        <ModalActions>
          <Button variant="default" onClick={() => setCancel(false)}>
            {t("import.cancel")}
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
