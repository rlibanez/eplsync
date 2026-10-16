import { WorkNavigation, useWorkContext } from "../jobs/WorkNavigation";
import { useJobRetry } from "../jobs/useJobRetry";
import { SelectionToolbar } from "../../components/SelectionToolbar";
import { PageTitle } from "../../components/PageTitle";
import { useEffect, useState } from "react";
import {
  Alert,
  Checkbox,
  Button,
  Group,
  Progress,
  Select,
  TextInput,
} from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Pause, Play, X } from "lucide-react";
import { BookCover } from "../catalog/BookCover";
import { dateBounds } from "../events/eventTypes";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";
import { useLocale } from "../../locales/useLocale";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { useDownloadColumns } from "../downloads/DownloadTable";
import { sortQuery } from "../downloads/TableControls";
import { Paging, post, type Page } from "../downloads/shared";

type Job = {
  type?: "SEND" | "CLEANUP";
  id: string;
  createdAt: string;
  updatedAt: string;
  username: string;
  library: string;
  state: string;
  message: string | null;
  total: number;
  pending: number;
  accepted: number;
  skipped: number;
  failed: number;
  uncertain: number;
};
type Item = {
  id: string;
  recordId: string | null;
  downloadJobId?: string | null;
  index: number;
  name: string;
  size: number;
  eplId: number;
  revision: string;
  state: string;
  calibreId: number | null;
  message: string | null;
};
function Controls({ job }: { job: Job }) {
  const { can } = useAuth();
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [cancel, setCancel] = useState(false);
  const control = useMutation({
    mutationFn: (command: string) =>
      post<Job>(`/calibre/transfers/${job.id}/${command}`),
    retry: false,
    onSuccess: () => {
      setCancel(false);
      void cache.invalidateQueries({ queryKey: ["calibre-jobs"] });
      void cache.invalidateQueries({ queryKey: ["calibre-job"] });
      void cache.invalidateQueries({ queryKey: ["calibre-job-items"] });
    },
  });
  if (!can("CALIBRE_JOBS_MANAGE")) return null;
  return (
    <>
      <Group gap="xs">
        {!["COMPLETED", "CANCELLED", "PAUSED"].includes(job.state) && (
          <Button
            variant="light"
            size="xs"
            leftSection={<Pause size={14} />}
            disabled={control.isPending}
            onClick={() => control.mutate("pause")}
          >
            {t("downloads.pause")}
          </Button>
        )}
        {(job.state === "PAUSED" ||
          (job.state === "CANCELLED" && job.uncertain > 0)) && (
          <Button
            variant="light"
            size="xs"
            leftSection={<Play size={14} />}
            disabled={control.isPending}
            onClick={() => control.mutate("resume")}
          >
            {t(job.uncertain ? "calibreSend.verify" : "downloads.resume")}
          </Button>
        )}
        {!["COMPLETED", "CANCELLED"].includes(job.state) && (
          <Button
            variant="light"
            color="red"
            size="xs"
            leftSection={<X size={14} />}
            disabled={control.isPending}
            onClick={() => setCancel(true)}
          >
            {t("calibreSend.cancel")}
          </Button>
        )}
      </Group>
      {control.isError && (
        <Alert color="red" mt="sm">
          {control.error.message || t("calibreSend.failed")}
        </Alert>
      )}
      <AppModal
        width="compact"
        opened={cancel}
        onClose={() => {
          if (!control.isPending) setCancel(false);
        }}
        title={t("calibreSend.cancel")}
        icon={X}
      >
        <p>{t("calibreSend.cancelHelp")}</p>
        <ModalActions>
          <Button
            variant="default"
            disabled={control.isPending}
            onClick={() => setCancel(false)}
          >
            {t("common.close")}
          </Button>
          <Button
            color="red"
            loading={control.isPending}
            onClick={() => control.mutate("cancel")}
          >
            {t("calibreSend.cancel")}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
export function CalibreJobs() {
  const { t } = useTranslation();
  const { date, number } = useLocale();
  const saved = useWorkContext("calibre")?.view;
  const [page, setPage] = useState(saved?.page ?? 0),
    [size, setSize] = useState(saved?.size ?? 20),
    [state, setState] = useState<string | null>(saved?.filter ?? null),
    [type, setType] = useState<string | null>(saved?.type ?? null),
    [username, setUsername] = useState(saved?.username ?? ""),
    [from, setFrom] = useState(saved?.from ?? ""),
    [to, setTo] = useState(saved?.to ?? ""),
    [sort, setSort] = useState(saved?.sort ?? "createdAt,desc");
  const bounds = dateBounds(from, to);
  const dates = new URLSearchParams();
  if (bounds.from) dates.set("from", bounds.from);
  if (bounds.before) dates.set("before", bounds.before);
  const choice = usePageSizeChoice(size);
  const result = useQuery({
    queryKey: [
      "calibre-jobs",
      page,
      size,
      state,
      type,
      username,
      from,
      to,
      sort,
    ],
    queryFn: ({ signal }) =>
      get<Page<Job>>(
        `/calibre/transfers?page=${page}&size=${size}&state=${state ?? ""}&type=${type ?? ""}&username=${encodeURIComponent(username.trim())}&${dates}&${sortQuery(sort)}`,
        signal,
      ),
    retry: false,
    refetchInterval: (query) => (query.state.status === "error" ? false : 5000),
  });
  const columns = useDownloadColumns(
    "eplsync.calibre.jobs.columns",
    [
      { field: "id", label: "downloads.job", width: 320 },
      { field: "createdAt", label: "downloads.createdAt", width: 200 },
      { field: "type", label: "downloads.type", width: 140 },
      { field: "library", label: "calibre.library", width: 180 },
      { field: "state", label: "downloads.status", width: 180 },
      { field: "total", label: "calibreSend.filesColumn", width: 100 },
      {
        field: "accepted",
        label: "revisionCleanup.sentOrRemoved",
        width: 160,
      },
      { field: "failed", label: "downloads.failedCount", width: 100 },
      { field: "username", label: "events.user", width: 180 },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
    },
    { defaults: "createdAt,desc" },
  );
  useEffect(() => {
    if (result.data)
      setPage((current) =>
        Math.min(current, Math.max(0, result.data.meta.totalPages - 1)),
      );
  }, [result.data]);
  return (
    <section>
      <div className="page-heading">
        <PageTitle
          title={t("calibreSend.jobs")}
          description={t("calibreSend.jobsHelp")}
        />
        <Button
          variant="filled"
          loading={result.isFetching}
          onClick={() => void result.refetch()}
        >
          {t(result.isFetching ? "downloads.refreshing" : "downloads.refresh")}
        </Button>
      </div>

      <div className="filters jobs-filters">
        <Select
          label={t("downloads.type")}
          value={type}
          clearable
          data={["SEND", "CLEANUP"].map((value) => ({
            value,
            label: t(`historyActions.jobTypes.${value}`),
          }))}
          onChange={(value) => {
            setType(value);
            setPage(0);
          }}
        />
        <Select
          label={t("downloads.status")}
          value={state}
          onChange={(value) => {
            setState(value);
            setPage(0);
          }}
          clearable
          data={["QUEUED", "RUNNING", "PAUSED", "COMPLETED", "CANCELLED"].map(
            (value) => ({ value, label: t(`calibreSend.states.${value}`) }),
          )}
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
          type="button"
          variant="subtle"
          onClick={() => {
            setState(null);
            setType(null);
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
                  {result.data.items.map((job) => (
                    <tr key={job.id}>
                      {columns.cells([
                        <td>
                          <Link
                            className="book-title"
                            state={{
                              workList: {
                                source: "calibre",
                                query: `state=${state ?? ""}&type=${type ?? ""}&username=${encodeURIComponent(username.trim())}&${dates}&${sortQuery(sort)}`,
                                view: {
                                  page,
                                  size,
                                  filter: state ?? "",
                                  type: type ?? "",
                                  username,
                                  from,
                                  to,
                                  sort,
                                },
                              },
                            }}
                            to={
                              job.type === "CLEANUP"
                                ? `/calibre/jobs/cleanup/${job.id}`
                                : `/calibre/jobs/${job.id}`
                            }
                          >
                            {job.id}
                          </Link>
                        </td>,
                        <td>{date(job.createdAt)}</td>,
                        <td>
                          <button
                            type="button"
                            className="catalog-value-filter"
                            onClick={() => {
                              setType(job.type ?? "SEND");
                              setPage(0);
                            }}
                          >
                            {t(`historyActions.jobTypes.${job.type ?? "SEND"}`)}
                          </button>
                        </td>,
                        <td>{job.library}</td>,
                        <td>
                          <button
                            type="button"
                            className="catalog-value-filter"
                            onClick={() => {
                              setState(job.state);
                              setPage(0);
                            }}
                          >
                            <span className="badge">
                              {t(`calibreSend.states.${job.state}`)}
                            </span>
                          </button>
                        </td>,
                        <td>{number(job.total)}</td>,
                        <td>{number(job.accepted)}</td>,
                        <td>{number(job.failed)}</td>,
                        <td>
                          <button
                            type="button"
                            className="catalog-value-filter"
                            onClick={() => {
                              setUsername(job.username);
                              setPage(0);
                            }}
                          >
                            {job.username}
                          </button>
                        </td>,
                      ])}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Paging
              choice={choice}
              meta={result.data.meta}
              page={page}
              size={size}
              onPage={setPage}
              onSize={(value) => {
                setSize(value);
                setPage(0);
              }}
            />
          </>
        )}
      </section>
    </section>
  );
}
export function CalibreJobDetail() {
  const { id } = useParams();
  const { t } = useTranslation();
  const { can } = useAuth();
  const { date, number, revision } = useLocale();
  const cache = useQueryClient();
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20),
    [sort, setSort] = useState("position,asc");
  const [itemState, setItemState] = useState<string | null>(null);
  const choice = usePageSizeChoice(size);
  const [selected, setSelected] = useState<string[]>([]);
  useEffect(() => setSelected([]), [id, itemState, sort]);
  const job = useQuery({
    queryKey: ["calibre-job", id],
    queryFn: ({ signal }) => get<Job>(`/calibre/transfers/${id}`, signal),
    retry: false,
    refetchInterval: (query) => (query.state.status === "error" ? false : 3000),
  });
  const retry = useJobRetry("calibre", id, job.data?.state, selected, () =>
    setSelected([]),
  );
  const result = useQuery({
    queryKey: ["calibre-job-items", id, page, size, sort, itemState],
    queryFn: ({ signal }) =>
      get<Page<Item>>(
        `/calibre/transfers/${id}/items?page=${page}&size=${size}&state=${itemState ?? ""}&${sortQuery(sort)}`,
        signal,
      ),
    retry: false,
    refetchInterval: (query) => (query.state.status === "error" ? false : 3000),
  });
  useEffect(() => {
    if (job.data)
      for (const key of ["calibre-library", "calibre-snapshot", "book"])
        void cache.invalidateQueries({ queryKey: [key] });
  }, [job.data?.updatedAt, cache]);
  useEffect(() => {
    if (result.data)
      setPage((current) =>
        Math.min(current, Math.max(0, result.data.meta.totalPages - 1)),
      );
  }, [result.data]);
  const columns = useDownloadColumns(
    "eplsync.calibre.job.items.columns",
    [
      { field: "eplId", label: "filters.eplId", width: 100 },
      { field: "name", label: "calibreSend.file", width: 420 },
      { field: "revision", label: "downloads.revision", width: 100 },
      { field: "state", label: "downloads.status", width: 180 },
      { field: "calibreId", label: "calibre.id", width: 100 },
      {
        field: "message",
        label: "calibreSend.detail",
        width: 360,
      },
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
        { field: "position", label: "calibreSend.position", width: 100 },
      ],
    },
  );
  if (job.isPending) return <Loading />;
  if (job.isError)
    return <Failure error={job.error} retry={() => job.refetch()} />;
  const value = job.data;
  return (
    <section>
      <WorkNavigation source="calibre" id={id} />
      <PageTitle
        title={t("calibreSend.job")}
        description={t("calibreSend.jobsHelp")}
      />
      <p className="hash-text">{id}</p>
      {retry.content}

      <section className="panel settings-section job-report-panel">
        {can("CALIBRE_JOBS_MANAGE") &&
          (!["COMPLETED", "CANCELLED"].includes(value.state) ||
            value.uncertain > 0) && (
            <div className="job-controls-heading">
              <div className="action-row">
                <Controls job={value} />
              </div>
            </div>
          )}
        <details className="report-collapse">
          <summary>
            <span>{t("calibreSend.report")}</span>
            <strong className="report-result">
              {t(`calibreSend.states.${value.state}`)}
            </strong>
          </summary>
          <div className="report-collapse-body">
            <Progress
              aria-label={t("calibreSend.progress")}
              value={
                value.total
                  ? (100 * (value.accepted + value.skipped + value.failed)) /
                    value.total
                  : 0
              }
            />
            <div className="job-report-groups">
              {[
                {
                  title: "jobReport.work",
                  values: [
                    ["events.user", value.username],
                    ["downloads.createdAt", date(value.createdAt)],
                    ["downloads.updatedAt", date(value.updatedAt)],
                    ["calibre.library", value.library],
                  ],
                },
                {
                  title: "jobReport.submission",
                  values: [
                    [
                      "calibreSend.progress",
                      `${number(value.accepted + value.skipped + value.failed)}/${number(value.total)}`,
                    ],
                    ["calibreSend.accepted", number(value.accepted)],
                    ["calibreSend.skipped", number(value.skipped)],
                    ["calibreSend.failedCount", number(value.failed)],
                    ["calibreSend.uncertain", number(value.uncertain)],
                  ],
                },
              ].map((group) => (
                <section className="job-report-group" key={group.title}>
                  <h3>{t(group.title)}</h3>
                  <dl className="import-summary">
                    {group.values.map(([label, content]) => (
                      <div key={label}>
                        <dt>{t(label)}</dt>
                        <dd>{content}</dd>
                      </div>
                    ))}
                  </dl>
                </section>
              ))}
            </div>
          </div>
        </details>
      </section>
      {value.message && (
        <Alert color="yellow" mb="md">
          {value.message}
        </Alert>
      )}
      <div className="filters jobs-filters">
        <Select
          label={t("downloads.status")}
          clearable
          value={itemState}
          data={[
            "PENDING",
            "WAITING_DOWNLOAD",
            "BLOCKED_DOWNLOAD",
            "SENDING",
            "ACCEPTED",
            "SKIPPED",
            "FAILED",
            "UNCERTAIN",
            "CLEANUP_PENDING",
            "CANCELLED",
          ].map((value) => ({
            value,
            label: t(`calibreSend.states.${value}`),
          }))}
          onChange={(value) => {
            setItemState(value);
            setPage(0);
          }}
        />
      </div>
      <section className="panel">
        <SelectionToolbar
          count={selected.length}
          onClear={() => setSelected([])}
          controls={columns.controls}
          actions={retry.action}
        />
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
                <thead>
                  {columns.selectionHeadings(
                    retry.selectable && (
                      <th>
                        <Checkbox
                          aria-label={t("selection.page")}
                          checked={
                            !!result.data?.items.length &&
                            result.data.items.every((row) =>
                              selected.includes(row.id),
                            )
                          }
                          onChange={(e) => {
                            const checked = e.currentTarget.checked;
                            setSelected((old) =>
                              checked
                                ? [
                                    ...new Set([
                                      ...old,
                                      ...(result.data?.items ?? []).map(
                                        (row) => row.id,
                                      ),
                                    ]),
                                  ]
                                : old.filter(
                                    (id) =>
                                      !result.data?.items.some(
                                        (row) => row.id === id,
                                      ),
                                  ),
                            );
                          }}
                        />
                      </th>
                    ),
                  )}
                </thead>
                <tbody>
                  {result.data.items.map((item) => (
                    <tr key={item.id}>
                      {retry.selectable && (
                        <td>
                          <Checkbox
                            aria-label={`EPL ${item.eplId}`}
                            checked={selected.includes(item.id)}
                            onChange={(e) => {
                              const checked = e.currentTarget.checked;
                              setSelected((old) =>
                                checked
                                  ? [...old, item.id]
                                  : old.filter((id) => id !== item.id),
                              );
                            }}
                          />
                        </td>
                      )}
                      {columns.cells([
                        <td>
                          {can("CATALOG_READ") ? (
                            <Link to={`/catalog/${item.eplId}`}>
                              {item.eplId}
                            </Link>
                          ) : (
                            item.eplId
                          )}
                        </td>,
                        <td className="catalog-title-cell">
                          <div className="book-title">
                            <BookCover
                              book={{
                                eplId: item.eplId,
                                title: item.name,
                                coverUrl: null,
                                coverAvailable: null,
                              }}
                            />
                            <span>
                              {can("CATALOG_READ") && item.eplId > 0 ? (
                                <Link to={`/catalog/${item.eplId}`}>
                                  {item.name}
                                </Link>
                              ) : (
                                item.name
                              )}
                            </span>
                          </div>
                        </td>,
                        <td>{revision(item.revision)}</td>,
                        <td>
                          <span className="badge">
                            {t(`calibreSend.states.${item.state}`)}
                          </span>
                        </td>,
                        <td>{item.calibreId ?? "—"}</td>,
                        <td>
                          {item.message ?? "—"}
                          {item.downloadJobId && can("TORRENT_JOBS_MANAGE") && (
                            <>
                              <br />
                              <Link
                                to={`/downloads/jobs/${item.downloadJobId}`}
                              >
                                {t("calibreUpdateSend.downloadJob")}
                              </Link>
                            </>
                          )}
                        </td>,
                      ])}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Paging
              choice={choice}
              meta={result.data.meta}
              page={page}
              size={size}
              onPage={setPage}
              onSize={(value) => {
                setSize(value);
                setPage(0);
              }}
            />
          </>
        )}
      </section>
    </section>
  );
}
