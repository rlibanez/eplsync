import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { CircleStop } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Select, Progress } from "@mantine/core";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import {
  Paging,
  ActionFailure,
  post,
  jobStates,
  itemStates,
  type Job,
  type Page,
} from "./shared";
export function Jobs() {
  const { t } = useTranslation();
  const { date, status, number } = useLocale();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [filter, setFilter] = useState("");
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
  }, [page, size, filter]);
  const result = useQuery({
    queryKey: ["jobs", page, size, filter],
    queryFn: ({ signal }) =>
      get<Page<Job>>(
        `/torrent/jobs?page=${page}&size=${size}${filter ? `&status=${filter}` : ""}`,
        signal,
      ),
    refetchInterval: 5000,
  });
  async function refreshJobs() {
    const request = ++refreshRequest.current;
    setRefresh("pending");
    try {
      const response = await result.refetch();
      if (request === refreshRequest.current)
        setRefresh(response.isError ? "error" : "success");
    } catch {
      if (request === refreshRequest.current) setRefresh("error");
    }
  }
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
      <div aria-live="polite" aria-atomic="true">
        {(refresh === "success" || refresh === "error") && (
          <Alert
            className="refresh-feedback"
            color={refresh === "success" ? "green" : "red"}
            role={refresh === "error" ? "alert" : "status"}
          >
            {t(
              refresh === "success"
                ? "downloads.refreshed"
                : "downloads.refreshError",
            )}
          </Alert>
        )}
      </div>
      <p className="muted">{t("downloads.jobNote")}</p>
      <div className="filters">
        <Select
          label={t("downloads.status")}
          clearable
          data={jobStates.map((value) => ({ value, label: status(value) }))}
          onChange={(v) => {
            setFilter(v ?? "");
            setPage(0);
          }}
        />
      </div>
      <section className="panel">
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
              <table>
                <thead>
                  <tr>
                    {[
                      "job",
                      "status",
                      "progress",
                      "selectedBooks",
                      "accepted",
                      "failed",
                      "createdAt",
                    ].map((key) => (
                      <th key={key}>
                        {t(
                          `downloads.${key === "failed" ? "failedCount" : key}`,
                        )}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.data.items.map((job) => (
                    <tr key={job.jobId}>
                      <td>
                        <Link
                          className="book-title"
                          to={`/downloads/jobs/${job.jobId}`}
                        >
                          {job.jobId}
                        </Link>
                      </td>
                      <td>{status(job.status)}</td>
                      <td>
                        {number(job.processedItems)} /{" "}
                        {number(job.selectedItems)}
                      </td>
                      <td>{number(job.selectedBooks)}</td>
                      <td>{number(job.accepted)}</td>
                      <td>{number(job.failed)}</td>
                      <td>{date(job.createdAt)}</td>
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
  const [cancel, setCancel] = useState(false);
  const job = useQuery({
    queryKey: ["job", id],
    queryFn: ({ signal }) =>
      get<Job>(`/torrent/jobs/${encodeURIComponent(id!)}`, signal),
    refetchInterval: (q) =>
      ["COMPLETED", "CANCELLED"].includes(q.state.data?.status ?? "")
        ? false
        : 3000,
  });
  const items = useQuery({
    queryKey: ["job-items", id, page, size, filter],
    queryFn: ({ signal }) =>
      get<Page<Item>>(
        `/torrent/jobs/${encodeURIComponent(id!)}/items?page=${page}&size=${size}${filter ? `&status=${filter}` : ""}`,
        signal,
      ),
    refetchInterval: ["COMPLETED", "CANCELLED"].includes(job.data?.status ?? "")
      ? false
      : 3000,
  });
  const control = useMutation({
    mutationFn: (action: string) =>
      post<Job>(`/torrent/jobs/${encodeURIComponent(id!)}/${action}`),
    retry: false,
    onSuccess: (data) => {
      cache.setQueryData(["job", id], data);
      void cache.invalidateQueries({ queryKey: ["jobs"] });
      void cache.invalidateQueries({ queryKey: ["job-items", id] });
    },
  });
  const data = job.data;
  return (
    <>
      <Link className="back-link" to="/downloads/jobs">
        ← {t("nav.jobs")}
      </Link>
      <h1>{t("downloads.job")}</h1>
      <p className="hash-text">{id}</p>
      <p className="muted">{t("downloads.jobNote")}</p>
      <ActionFailure error={control.error} />
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
          <section className="panel settings-section">
            <h2>{status(data.status)}</h2>
            <Progress
              aria-label={t("downloads.progress")}
              value={
                data.selectedItems
                  ? (100 * data.processedItems) / data.selectedItems
                  : 0
              }
            />
            <dl className="import-summary">
              {(
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
                  "batchSize",
                  "concurrency",
                ] as const
              ).map((key) => (
                <div key={key}>
                  <dt>
                    {t(`downloads.${key === "failed" ? "failedCount" : key}`)}
                  </dt>
                  <dd>{number(data[key])}</dd>
                </div>
              ))}
              <div>
                <dt>{t("downloads.createdAt")}</dt>
                <dd>{date(data.createdAt)}</dd>
              </div>
              <div>
                <dt>{t("downloads.updatedAt")}</dt>
                <dd>{date(data.updatedAt)}</dd>
              </div>
              {data.retryAt && (
                <div>
                  <dt>{t("downloads.retryAt")}</dt>
                  <dd>{date(data.retryAt)}</dd>
                </div>
              )}
              <div>
                <dt>{t("send.interval")}</dt>
                <dd>{data.interval}</dd>
              </div>
              <div>
                <dt>{t("send.multipleHashes")}</dt>
                <dd>{data.multipleHashes}</dd>
              </div>
            </dl>
            {data.message && <Alert>{data.message}</Alert>}
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
          </section>
        )
      )}
      <div className="filters">
        <Select
          label={t("downloads.itemStatus")}
          clearable
          data={itemStates.map((value) => ({ value, label: status(value) }))}
          onChange={(v) => {
            setFilter(v ?? "");
            setPage(0);
          }}
        />
      </div>
      <section className="panel">
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
              <table>
                <thead>
                  <tr>
                    {["book", "hash", "status", "attempts", "message"].map(
                      (key) => (
                        <th key={key}>
                          {t(
                            `downloads.${key === "failed" ? "failedCount" : key}`,
                          )}
                        </th>
                      ),
                    )}
                  </tr>
                </thead>
                <tbody>
                  {items.data.items.map((item) => (
                    <tr key={item.id}>
                      <td>
                        <Link to={`/catalog/${item.eplId}`}>
                          EPL {item.eplId}
                        </Link>
                      </td>
                      <td className="hash-text">{item.hash}</td>
                      <td>{status(item.status)}</td>
                      <td>{item.attempts}</td>
                      <td>{item.message || "—"}</td>
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
