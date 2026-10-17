import { RowNumberHeading, RowNumberCell } from "../../components/RowNumber";
import { WorkNavigation } from "../jobs/WorkNavigation";
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Group,
  Progress,
  Select,
  Stepper,
} from "@mantine/core";
import { Eraser } from "lucide-react";
import {
  Link,
  Navigate,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { PageTitle } from "../../components/PageTitle";
import { Failure, Loading } from "../../components/Feedback";
import { get } from "../../api/catalog";
import { post, type Page } from "../downloads/shared";
import { useAuth } from "../auth/Auth";

type Source = "downloads" | "calibre";
interface Job {
  id: string;
  source: Source;
  action: string;
  state: string;
  result: string | null;
  message: string | null;
  library: string | null;
  allowUndownloaded?: boolean;
  deleteHistory?: boolean;
  directRemoval?: boolean;
  createdAt: number;
  total: number;
  selected: number;
  ready: number;
  excluded: number;
  pending: number;
  succeeded: number;
  blocked: number;
  failed: number;
  uncertain: number;
}
interface Item {
  id: string;
  eplId: number;
  title: string;
  revision: string;
  oldId: string;
  replacementId: string;
  replacementRevision: string;
  formats: string[];
  state: string;
  message: string | null;
  dispatched: boolean;
  blockingCalibreJobId?: string | null;
}
const active = (job?: Job) =>
  !!job && ["QUEUED", "RUNNING"].includes(job.state);
function ErrorMessage({ error }: { error: unknown }) {
  const { t } = useTranslation();
  return error ? (
    <Alert color="red" role="alert">
      {error instanceof Error && error.message
        ? error.message
        : t("revisionCleanup.error")}
    </Alert>
  ) : null;
}
function useActions(source: Source, direct = false, deleteHistory = false) {
  const auth = useAuth();
  if (source === "calibre")
    return auth.can("CALIBRE_READ") && auth.can("CALIBRE_DELETE")
      ? ["BOOK"]
      : [];
  if (
    !auth.can("TORRENT_SYNC") ||
    (!direct && !auth.can("DOWNLOADS_FILES_READ")) ||
    ((!direct || deleteHistory) && !auth.can("DOWNLOADS_DELETE"))
  )
    return [];
  return [
    ...(auth.can("DOWNLOADS_DELETE") ? ["RECORD"] : []),
    ...(auth.can("TORRENT_CLEANUP")
      ? ["TORRENT", ...(auth.can("TORRENT_FILES_DELETE") ? ["FILES"] : [])]
      : []),
  ];
}
export function CleanupLauncher({
  source,
  selected,
}: {
  source: Source;
  selected: (string | number)[];
}) {
  const { t } = useTranslation();
  const actions = useActions(source);
  const [opened, setOpened] = useState(false);
  if (!actions.length) return null;
  return (
    <>
      <Button
        variant="default"
        leftSection={<Eraser size={16} />}
        onClick={() => setOpened(true)}
      >
        {t("revisionCleanup.title")}
      </Button>
      {opened && (
        <CleanupWizard
          source={source}
          selected={selected.map(String)}
          actions={actions}
          close={() => setOpened(false)}
        />
      )}
    </>
  );
}
function CleanupWizard({
  source,
  selected,
  actions,
  close,
}: {
  source: Source;
  selected: string[];
  actions: string[];
  close: () => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const [step, setStep] = useState(0);
  const [action, setAction] = useState(
    actions.includes("TORRENT") ? "TORRENT" : actions[0],
  );
  const [deleteHistory, setDeleteHistory] = useState(false);
  const [allowUndownloaded, setAllowUndownloaded] = useState(false);
  const [scope, setScope] = useState(selected.length ? "selected" : "all");
  const [preview, setPreview] = useState<Job>();
  const [rows, setRows] = useState<Item[]>([]);
  const [checked, setChecked] = useState<string[]>([]);
  const [confirm, setConfirm] = useState(false);
  const [destructive, setDestructive] = useState(false);
  const prepare = useMutation({
    mutationFn: async () => {
      const job = await post<Job>("/revision-cleanups/preview", {
        source,
        action: action === "RECORD" ? "KEEP" : action,
        allowUndownloaded,
        deleteHistory,
        ids: scope === "selected" ? selected : [],
      });
      const items = await get<Page<Item>>(
        `/revision-cleanups/${job.id}/items?size=1000`,
      );
      setPreview(job);
      setRows(items.items);
      setChecked(
        items.items
          .filter((item) => item.state === "READY")
          .map((item) => item.id),
      );
      setStep(1);
    },
  });
  const start = useMutation({
    mutationFn: () =>
      post<Job>(`/revision-cleanups/${preview!.id}/start`, {
        ids: checked,
        confirm,
        confirmDestructive: destructive,
      }),
    onSuccess: (job) => {
      ["revision-cleanups", "jobs", "calibre-jobs"].forEach(
        (key) => void cache.invalidateQueries({ queryKey: [key] }),
      );
      close();
      navigate(
        `/${source === "calibre" ? "calibre" : "downloads"}/jobs/cleanup/${job.id}`,
      );
    },
  });
  const busy = prepare.isPending || start.isPending;
  return (
    <AppModal
      opened
      onClose={busy ? () => {} : close}
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      title={t("revisionCleanup.title")}
      icon={Eraser}
      width="wide"
    >
      <Stepper active={step} size="xs" allowNextStepsSelect={false}>
        <Stepper.Step label={t("revisionCleanup.configure")} />
        <Stepper.Step label={t("revisionCleanup.preview")} />
        <Stepper.Step label={t("revisionCleanup.confirm")} />
      </Stepper>
      {step === 0 && (
        <>
          <p>{t("revisionCleanup.protection")}</p>
          <p className="muted">{t("revisionCleanup.syncFirst")}</p>
          {source === "calibre" ? (
            <p>
              <strong>{t("revisionCleanup.actions.BOOK")}</strong>
            </p>
          ) : (
            <>
              <Select
                label={t("revisionCleanup.action")}
                value={action}
                allowDeselect={false}
                onChange={(value) => value && setAction(value)}
                data={actions.map((value) => ({
                  value,
                  label: t(
                    value === "RECORD"
                      ? "send.keepPrevious"
                      : `revisionCleanup.actions.${value}`,
                  ),
                }))}
              />
            </>
          )}
          {source === "downloads" && (
            <>
              <Checkbox
                mt="sm"
                label={t("send.deleteHistory")}
                checked={deleteHistory}
                onChange={(event) =>
                  setDeleteHistory(event.currentTarget.checked)
                }
              />
              <Checkbox
                mt="sm"
                checked={allowUndownloaded}
                onChange={(event) =>
                  setAllowUndownloaded(event.currentTarget.checked)
                }
                label={t("revisionCleanup.allowUndownloaded")}
              />
            </>
          )}
          {!!selected.length && (
            <Select
              label={t("revisionCleanup.scope")}
              value={scope}
              allowDeselect={false}
              onChange={(value) => value && setScope(value)}
              data={[
                {
                  value: "selected",
                  label: t("revisionCleanup.selectedScope", {
                    count: selected.length,
                  }),
                },
                { value: "all", label: t("revisionCleanup.allScope") },
              ]}
            />
          )}
        </>
      )}
      {step === 1 && (
        <>
          <p>
            {t("revisionCleanup.previewSummary", {
              ready: preview?.ready,
              excluded: preview?.excluded,
            })}
          </p>
          <Group gap="xs">
            <Button
              variant="subtle"
              onClick={() =>
                setChecked(
                  rows
                    .filter((item) => item.state === "READY")
                    .map((item) => item.id),
                )
              }
            >
              {t("revisionCleanup.selectAll")}
            </Button>
            <Button variant="subtle" onClick={() => setChecked([])}>
              {t("revisionCleanup.clear", { count: checked.length })}
            </Button>
          </Group>
          <CleanupItems rows={rows} selected={checked} onSelect={setChecked} />
        </>
      )}
      {step === 2 && (
        <>
          <p>
            <strong>
              {t("revisionCleanup.confirmCount", { count: checked.length })}
            </strong>
          </p>
          <p>
            {t(
              action === "RECORD"
                ? "send.keepPrevious"
                : `revisionCleanup.actions.${action}`,
            )}
          </p>
          {source === "downloads" && (
            <p>
              {t(deleteHistory ? "send.deleteHistory" : "send.keepHistory")}
            </p>
          )}
          <p>
            {t(
              allowUndownloaded
                ? "revisionCleanup.allowUndownloaded"
                : "revisionCleanup.protection",
            )}
          </p>
          <Checkbox
            label={t("revisionCleanup.confirmSelection")}
            checked={confirm}
            onChange={(event) => setConfirm(event.currentTarget.checked)}
          />
          {["FILES", "BOOK"].includes(action) && (
            <Checkbox
              mt="sm"
              label={t("revisionCleanup.confirmDestructive")}
              checked={destructive}
              onChange={(event) => setDestructive(event.currentTarget.checked)}
            />
          )}
        </>
      )}
      <ErrorMessage error={prepare.error || start.error} />
      <ModalActions
        summary={
          step === 1
            ? t("revisionCleanup.count", { count: checked.length })
            : undefined
        }
      >
        <Button
          variant="default"
          disabled={busy}
          onClick={
            step
              ? () => {
                  setStep(step - 1);
                  setConfirm(false);
                  setDestructive(false);
                }
              : close
          }
        >
          {t(step ? "revisionCleanup.back" : "revisionCleanup.close")}
        </Button>
        {step === 0 ? (
          <Button
            disabled={
              source === "downloads" && action === "RECORD" && !deleteHistory
            }
            loading={prepare.isPending}
            onClick={() => prepare.mutate()}
          >
            {t("revisionCleanup.preview")}
          </Button>
        ) : step === 1 ? (
          <Button disabled={!checked.length} onClick={() => setStep(2)}>
            {t("revisionCleanup.next")}
          </Button>
        ) : (
          <Button
            color="red"
            loading={start.isPending}
            disabled={
              !confirm || (["FILES", "BOOK"].includes(action) && !destructive)
            }
            onClick={() => start.mutate()}
          >
            {t("revisionCleanup.create")}
          </Button>
        )}
      </ModalActions>
    </AppModal>
  );
}
function CleanupItems({
  rows,
  offset = 0,
  direct = false,
  selected,
  onSelect,
}: {
  rows: Item[];
  offset?: number;
  direct?: boolean;
  selected?: string[];
  onSelect?: (ids: string[]) => void;
}) {
  const { t } = useTranslation();
  const auth = useAuth();
  return (
    <div className="table-scroll revision-cleanup-table">
      <table>
        <thead>
          <tr>
            {onSelect && <th aria-label={t("revisionCleanup.selection")} />}
            <RowNumberHeading />
            <th>EPL ID</th>
            <th>{t("revisionCleanup.book")}</th>
            <th>{t("revisionCleanup.remove")}</th>
            {!direct && <th>{t("revisionCleanup.keep")}</th>}
            <th>{t("revisionCleanup.detail")}</th>
          </tr>
        </thead>
        <tbody>
          {!rows.length && (
            <tr>
              <td colSpan={onSelect ? 7 : 6}>{t("revisionCleanup.empty")}</td>
            </tr>
          )}
          {rows.map((item, rowIndex) => (
            <tr key={item.id}>
              {onSelect && (
                <td>
                  <Checkbox
                    aria-label={t("revisionCleanup.selectBook", {
                      title: item.title,
                    })}
                    checked={selected?.includes(item.id) || false}
                    disabled={item.state !== "READY"}
                    onChange={(event) =>
                      onSelect(
                        event.currentTarget.checked
                          ? [...selected!, item.id]
                          : selected!.filter((id) => id !== item.id),
                      )
                    }
                  />
                </td>
              )}
              <RowNumberCell value={offset + rowIndex + 1} />
              <td>{item.eplId}</td>
              <td>
                {auth.can("CATALOG_READ") ? (
                  <Link to={`/catalog/${item.eplId}`}>{item.title}</Link>
                ) : (
                  item.title
                )}
              </td>
              <td>
                r{item.revision}
                <br />
                <small>ID: {item.oldId}</small>
                {!!item.formats.length && (
                  <>
                    <br />
                    <small>{item.formats.join(", ")}</small>
                  </>
                )}
              </td>
              {!direct && (
                <td>
                  {item.replacementId ? (
                    <>
                      r{item.replacementRevision}
                      <br />
                      <small>ID: {item.replacementId}</small>
                    </>
                  ) : (
                    "—"
                  )}
                </td>
              )}
              <td>
                <Badge
                  variant="light"
                  color={
                    item.state === "EXCLUDED" || item.state === "SKIPPED"
                      ? "gray"
                      : item.state === "FAILED"
                        ? "red"
                        : item.state === "UNCERTAIN" || item.state === "BLOCKED"
                          ? "yellow"
                          : "teal"
                  }
                >
                  {t(`revisionCleanup.states.${item.state}`)}
                </Badge>
                {item.message && <p className="muted">{item.message}</p>}
                {item.blockingCalibreJobId && (
                  <Link
                    to={`/calibre/jobs/${encodeURIComponent(item.blockingCalibreJobId)}`}
                  >
                    {t("calibreUpdateSend.calibreJob")}
                  </Link>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function Pager({
  page,
  pages,
  change,
}: {
  page: number;
  pages: number;
  change: (page: number) => void;
}) {
  const { t } = useTranslation();
  return (
    <Group mt="md">
      <Button
        variant="default"
        disabled={!page}
        onClick={() => change(page - 1)}
      >
        {t("revisionCleanup.back")}
      </Button>
      <span>
        {page + 1} / {Math.max(1, pages)}
      </span>
      <Button
        variant="default"
        disabled={page + 1 >= pages}
        onClick={() => change(page + 1)}
      >
        {t("revisionCleanup.next")}
      </Button>
    </Group>
  );
}
export function CleanupJobs() {
  const [params] = useSearchParams();
  return (
    <Navigate
      replace
      to={
        params.get("source") === "calibre" ? "/calibre/jobs" : "/downloads/jobs"
      }
    />
  );
}
export function CleanupJob() {
  const { id } = useParams();
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [page, setPage] = useState(0);
  const retryRequest = useRef("");
  useEffect(() => {
    retryRequest.current = "";
  }, [id]);
  const job = useQuery({
    queryKey: ["revision-cleanup", id],
    queryFn: () => get<Job>(`/revision-cleanups/${id}`),
    refetchInterval: (q) => (active(q.state.data) ? 2000 : false),
  });
  const rows = useQuery({
    queryKey: ["revision-cleanup-items", id, page],
    queryFn: () =>
      get<Page<Item>>(`/revision-cleanups/${id}/items?page=${page}&size=20`),
    enabled: !!job.data,
    refetchInterval: active(job.data) ? 2000 : false,
  });
  useEffect(() => {
    void cache.invalidateQueries({ queryKey: ["revision-cleanup-items", id] });
  }, [job.data?.state, job.data?.result, id, cache]);
  const command = useMutation({
    mutationFn: (value: string) =>
      post<Job>(
        `/revision-cleanups/${id}/${value}`,
        value === "retry"
          ? {
              requestId: retryRequest.current,
              confirm: true,
              confirmDestructive: true,
            }
          : undefined,
      ),
    onSuccess: () => {
      retryRequest.current = "";
      void job.refetch();
      void rows.refetch();
      [
        "downloads",
        "calibre-library",
        "revision-cleanups",
        "jobs",
        "calibre-jobs",
      ].forEach((key) => void cache.invalidateQueries({ queryKey: [key] }));
    },
  });
  const actions = useActions(
    job.data?.source || "downloads",
    job.data?.directRemoval,
    job.data?.deleteHistory,
  );
  if (job.isPending) return <Loading />;
  if (job.isError)
    return <Failure error={job.error} retry={() => void job.refetch()} />;
  const data = job.data;
  const statusColor =
    data.result === "FAILED"
      ? "red"
      : data.result === "PARTIAL" ||
          data.uncertain > 0 ||
          data.blocked > 0 ||
          data.failed > 0
        ? "orange"
        : data.state === "CANCELLED"
          ? "gray"
          : active(data)
            ? "blue"
            : "teal";
  const metrics = [
    ["selected", data.selected],
    ["removed", data.succeeded],
    ["pending", data.pending],
    ["blocked", data.blocked],
    ["failed", data.failed],
    ["uncertain", data.uncertain],
  ] as const;
  return (
    <>
      <WorkNavigation source={data.source} id={id} />
      <PageTitle
        title={t(
          data.directRemoval
            ? "revisionCleanup.removalJobTitle"
            : "revisionCleanup.jobTitle",
        )}
        description={t(
          data.directRemoval
            ? "revisionCleanup.removalHelp"
            : "revisionCleanup.protection",
        )}
      />
      <p className="hash-text">{id}</p>
      <section className="panel settings-section job-report-panel">
        <div className="job-controls-heading">
          <div className="action-row">
            {(["QUEUED", "RUNNING", "PAUSED"].includes(data.state) ||
              (data.directRemoval && data.blocked + data.failed > 0)) && (
              <Button
                variant="default"
                disabled={command.isPending}
                onClick={() => command.mutate("cancel")}
              >
                {t("revisionCleanup.cancel")}
              </Button>
            )}
            {(actions.includes(data.action) ||
              (data.action === "EPUB" && actions.includes("BOOK"))) && (
              <>
                {(data.state === "PAUSED" ||
                  (data.directRemoval &&
                    data.blocked + data.failed > 0 &&
                    !active(data))) &&
                  actions.includes(data.action) && (
                    <Button
                      disabled={command.isPending}
                      onClick={() => command.mutate("continue")}
                    >
                      {t("revisionCleanup.continue")}
                    </Button>
                  )}
                {data.source === "downloads" &&
                  data.action !== "RECORD" &&
                  actions.includes(data.action) &&
                  data.uncertain > 0 &&
                  data.pending === 0 &&
                  !active(data) && (
                    <Button
                      variant="default"
                      disabled={command.isPending}
                      onClick={() => {
                        if (!retryRequest.current)
                          retryRequest.current = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
                        command.mutate("retry");
                      }}
                    >
                      {t("revisionCleanup.retry")}
                    </Button>
                  )}
                {data.uncertain > 0 && data.pending === 0 && !active(data) && (
                  <Button
                    disabled={command.isPending}
                    onClick={() => command.mutate("verify")}
                  >
                    {t("revisionCleanup.verify")}
                  </Button>
                )}
              </>
            )}
          </div>
        </div>
        <details className="report-collapse">
          <summary>
            <span>
              {t(
                data.source === "downloads"
                  ? "downloads.report"
                  : "calibreSend.report",
              )}
            </span>
            <Badge
              color={statusColor}
              variant="light"
              data-color={statusColor}
              className="cleanup-job-status"
            >
              {t(`revisionCleanup.states.${data.state}`)}
              {data.result && ` · ${t(`events.outcomes.${data.result}`)}`}
            </Badge>
          </summary>
          <div className="report-collapse-body">
            <Progress
              aria-label={t("revisionCleanup.progressColumn")}
              value={
                data.selected
                  ? (100 * (data.selected - data.pending)) / data.selected
                  : 0
              }
            />
            <div className="job-report-groups">
              <section className="job-report-group">
                <h3>{t("jobReport.work")}</h3>
                <dl className="import-summary">
                  <div>
                    <dt>{t("revisionCleanup.action")}</dt>
                    <dd>
                      {t(
                        data.action === "RECORD"
                          ? "send.keepPrevious"
                          : `revisionCleanup.actions.${data.action}`,
                      )}
                    </dd>
                  </div>
                  {data.source === "downloads" && (
                    <div>
                      <dt>{t("send.deleteHistory")}</dt>
                      <dd>
                        {t(
                          data.deleteHistory ? "downloads.yes" : "downloads.no",
                        )}
                      </dd>
                    </div>
                  )}
                  {data.library && (
                    <div>
                      <dt>{t("calibre.library")}</dt>
                      <dd>{data.library}</dd>
                    </div>
                  )}
                  {data.allowUndownloaded && (
                    <div>
                      <dt>{t("revisionCleanup.downloadRequirement")}</dt>
                      <dd>{t("revisionCleanup.allowUndownloaded")}</dd>
                    </div>
                  )}
                </dl>
              </section>
              <section
                className="job-report-group"
                aria-label={t("revisionCleanup.summary")}
              >
                <h3>{t("jobReport.cleanup")}</h3>
                <dl className="import-summary cleanup-job-counts">
                  {metrics.map(([name, value]) => (
                    <div key={name}>
                      <dt>{t(`revisionCleanup.metrics.${name}`)}</dt>
                      <dd>{value}</dd>
                    </div>
                  ))}
                </dl>
              </section>
            </div>
            {data.message && <Alert color={statusColor}>{data.message}</Alert>}
          </div>
        </details>
      </section>
      <ErrorMessage error={command.error} />
      {rows.isError ? (
        <Failure error={rows.error} retry={() => void rows.refetch()} />
      ) : rows.isPending ? (
        <Loading />
      ) : (
        <>
          <CleanupItems
            rows={rows.data.items}
            direct={data.directRemoval}
            offset={page * 20}
          />
          <Pager
            page={page}
            pages={rows.data.meta.totalPages}
            change={setPage}
          />
        </>
      )}
    </>
  );
}
