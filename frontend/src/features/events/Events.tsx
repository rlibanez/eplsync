import { eventActorLabel } from "./eventTypes";
import { useAuth } from "../auth/Auth";
import { EventSummary } from "./EventSummary";
import { readEventNavigation, saveEventNavigation } from "./eventNavigation";
import { useDownloadColumns } from "../downloads/DownloadTable";
import { ordering } from "../downloads/TableControls";
import { markEventsRead, markOperationRead } from "./useUnreadEvents";
import { useEffect, useState, useRef } from "react";
import {
  useQuery,
  useQueryClient,
  keepPreviousData,
} from "@tanstack/react-query";
import {
  Badge,
  Button,
  Group,
  Pagination,
  Select,
  TextInput,
  Tooltip,
} from "@mantine/core";
import { Link, useSearchParams } from "react-router-dom";
import { RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Failure, Loading } from "../../components/Feedback";
import {
  dateBounds,
  operationOutcome,
  formatDuration,
  type OperationPage,
} from "./eventTypes";

export function Events() {
  const [search] = useSearchParams();
  const operationId = search.get("operationId") || undefined;
  return <EventsView key={operationId ?? "all"} operationId={operationId} />;
}

function EventsView({ operationId }: { operationId?: string }) {
  const auth = useAuth();
  const [saved] = useState(() =>
    operationId
      ? {
          action: null,
          username: "",
          category: null,
          outcome: null,
          origin: null,
          from: "",
          to: "",
          page: 0,
          sort: "startedAt,desc",
          expanded: [operationId],
          scroll: 0,
        }
      : readEventNavigation(),
  );
  const [visit] = useState(() => Math.random());
  const snapshot = useRef<number | undefined>(undefined);
  const restored = useRef(false);
  const cache = useQueryClient();

  const [visible, setVisible] = useState(
    document.visibilityState === "visible",
  );
  useEffect(() => {
    const update = () => setVisible(document.visibilityState === "visible");
    document.addEventListener("visibilitychange", update);
    return () => document.removeEventListener("visibilitychange", update);
  }, []);
  const { t, i18n } = useTranslation();
  const [action, setAction] = useState<string | null>(saved.action);
  const [category, setCategory] = useState<string | null>(saved.category);
  const [outcome, setOutcome] = useState<string | null>(saved.outcome);
  const [origin, setOrigin] = useState<string | null>(saved.origin);
  const [username, setUsername] = useState(saved.username);
  const [from, setFrom] = useState(saved.from);
  const [to, setTo] = useState(saved.to);
  const [page, setPage] = useState(saved.page);
  const [sort, setSort] = useState(saved.sort);
  const columns = useDownloadColumns(
    "eplsync.events.columnWidths",
    [
      { field: "startedAt", label: "events.startedAt", width: 195 },
      { field: "finishedAt", label: "events.finishedAt", width: 195 },
      { field: "duration", label: "events.duration", width: 110 },
      { field: "category", label: "events.category", width: 140 },
      { field: "event", label: "events.event", width: 195 },
      { field: "outcome", label: "events.outcome", width: 170 },
      { field: "origin", label: "events.origin", width: 120 },
      { field: "user", label: "events.user", width: 170 },
      {
        field: "summary",
        label: "events.summary",
        width: 480,
        sortable: false,
      },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
    },
    { defaults: "startedAt,desc" },
  );
  const [expanded, setExpanded] = useState(saved.expanded);
  const navigation = useRef(saved);
  navigation.current = {
    action,
    category,
    outcome,
    origin,
    username,
    from,
    to,
    page,
    expanded,
    sort,
    scroll: navigation.current.scroll,
  };
  useEffect(() => {
    if (!operationId) saveEventNavigation(navigation.current);
  }, [
    action,
    category,
    outcome,
    origin,
    username,
    from,
    to,
    page,
    expanded,
    operationId,
    sort,
  ]);
  useEffect(() => {
    const storeScroll = () => {
      if (!restored.current) return;
      navigation.current.scroll = window.scrollY;
      if (!operationId) saveEventNavigation(navigation.current);
    };
    window.addEventListener("scroll", storeScroll, { passive: true });
    return () => window.removeEventListener("scroll", storeScroll);
  }, []);
  const changes = useQuery<{ cursor: number; revision: number }>({
    queryKey: ["event-changes"],
    enabled: false,
    initialData: { cursor: 0, revision: 0 },
  });
  const params = new URLSearchParams({ page: String(page), size: "20" });
  ordering(sort).forEach((value) => params.append("sort", value));
  if (operationId) params.set("operationId", operationId);
  if (action) params.set("action", action);
  if (category) params.set("category", category);
  if (outcome) params.set("outcome", outcome);
  if (origin) params.set("origin", origin);
  if (username.trim()) params.set("username", username.trim());
  const bounds = dateBounds(from, to);
  if (bounds.from) params.set("from", bounds.from);
  if (bounds.before) params.set("before", bounds.before);
  const result = useQuery({
    placeholderData: keepPreviousData,
    queryKey: ["event-operations", visit, params.toString()],
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    queryFn: async ({ signal }) => {
      const request = new URLSearchParams(params);
      if (snapshot.current !== undefined)
        request.set("snapshot", String(snapshot.current));
      const revision =
        cache.getQueryData<{ revision: number }>(["event-changes"])?.revision ??
        0;
      const data = await get<OperationPage>(
        "/events/operations?" + request,
        signal,
      );
      if (!signal.aborted) snapshot.current = data.cursor;
      return { ...data, revision };
    },
  });
  const pending =
    result.data &&
    (changes.data.cursor > result.data.cursor ||
      changes.data.revision !== result.data.revision);
  const refresh = () => {
    snapshot.current = undefined;
    void result.refetch();
  };
  useEffect(() => {
    if (!result.data || result.isFetching || restored.current) return;
    const frame = requestAnimationFrame(() => {
      window.scrollTo(0, saved.scroll);
      restored.current = true;
    });
    return () => cancelAnimationFrame(frame);
  }, [result.data, result.isFetching, saved.scroll]);
  useEffect(() => {
    // Mark only when the displayed result/visibility changes. A server reset may
    // lower the read cursor while this table deliberately retains its snapshot.
    if (visible && result.data && !result.isFetching) {
      if (operationId) {
        const selected = result.data.items.find(
          (item) => item.latest.operationId === operationId,
        );
        if (selected) markOperationRead(auth.user?.id, selected.latest.id);
      } else markEventsRead(auth.user?.id, result.data.cursor);
    }
    if (result.data)
      setPage((current) =>
        Math.min(current, Math.max(0, Math.ceil(result.data.total / 20) - 1)),
      );
  }, [result.data, result.isFetching, visible, operationId, auth.user?.id]);
  const filterValue = (
    field: "action" | "category" | "outcome" | "origin",
    value: string,
    label: string,
    content = <>{label}</>,
  ) => {
    const help = t("catalog.filterByValue", {
      field: t("events." + (field === "action" ? "event" : field)),
      value: label,
    });
    return (
      <Tooltip label={help}>
        <button
          type="button"
          className="catalog-value-filter"
          aria-label={help}
          onClick={() => {
            ({
              action: setAction,
              category: setCategory,
              outcome: setOutcome,
              origin: setOrigin,
            })[field](value);
            setPage(0);
          }}
        >
          {content}
        </button>
      </Tooltip>
    );
  };
  return (
    <>
      <div className="page-heading">
        <h1>{t("events.title")}</h1>
        {pending && (
          <Button
            leftSection={<RefreshCw size={16} />}
            onClick={refresh}
            loading={result.isFetching}
            title={t("events.newEvents")}
          >
            {t("events.refresh")}
          </Button>
        )}
      </div>
      <p className="muted">{t("events.description")}</p>
      {operationId ? (
        <Button component={Link} to="/events" variant="subtle" mb="md">
          {t("events.showAll")}
        </Button>
      ) : (
        <div className="filters">
          <TextInput
            type="date"
            label={t("filters.from")}
            value={from}
            max={to || undefined}
            onChange={(e) => {
              setFrom(e.currentTarget.value);
              setPage(0);
            }}
          />
          <TextInput
            type="date"
            label={t("filters.to")}
            value={to}
            min={from || undefined}
            onChange={(e) => {
              setTo(e.currentTarget.value);
              setPage(0);
            }}
          />
          <Select
            label={t("events.category")}
            clearable
            value={category}
            data={[
              "CATALOG",
              "JOB",
              "COVERS",
              "TORRENT",
              ...(auth.user?.role === "ADMIN" ? ["SECURITY"] : []),
            ].map((value) => ({
              value,
              label: t("events.categories." + value),
            }))}
            onChange={(value) => {
              setCategory(value);
              setPage(0);
            }}
          />
          <Select
            label={t("events.event")}
            clearable
            searchable
            value={action}
            data={Object.entries(
              t("events.actions", { returnObjects: true }) as Record<
                string,
                string
              >,
            )
              .filter(
                ([value]) =>
                  auth.user?.role === "ADMIN" || !value.startsWith("USER_"),
              )
              .map(([value, label]) => ({ value, label }))}
            onChange={(value) => {
              setAction(value);
              setPage(0);
            }}
          />

          <Select
            label={t("events.outcome")}
            clearable
            value={outcome}
            data={[
              "STARTED",
              "SUCCEEDED",
              "PARTIAL",
              "FAILED",
              "PAUSED",
              "RETRY_WAIT",
              "CANCELLED",
            ].map((value) => ({
              value,
              label:
                value === "STARTED"
                  ? t("events.running")
                  : t("events.outcomes." + value),
            }))}
            onChange={(value) => {
              setOutcome(value);
              setPage(0);
            }}
          />
          <Select
            label={t("events.origin")}
            clearable
            value={origin}
            data={["MANUAL", "SCHEDULED", "SYSTEM"].map((value) => ({
              value,
              label: t("events.origins." + value),
            }))}
            onChange={(value) => {
              setOrigin(value);
              setPage(0);
            }}
          />
          <TextInput
            label={t("events.user")}
            value={username}
            onChange={(e) => {
              setUsername(e.currentTarget.value);
              setPage(0);
            }}
          />
          <Button
            variant="subtle"
            onClick={() => {
              setAction(null);
              setCategory(null);
              setOutcome(null);
              setOrigin(null);
              setUsername("");
              setFrom("");
              setTo("");
              setPage(0);
            }}
          >
            {t("catalog.clear")}
          </Button>
        </div>
      )}
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : (
        <section className="panel events-panel">
          <div className="table-toolbar">
            <strong>
              {t("events.operationsTotal", { count: result.data.total })}
            </strong>
            <span className="sr-only" role="status">
              {pending ? t("events.newEvents") : ""}
            </span>
            <div className="catalog-table-controls">{columns.controls}</div>
          </div>
          {!result.data.items.length ? (
            <p>{t(operationId ? "events.missingOperation" : "events.empty")}</p>
          ) : (
            <div className="table-scroll">
              <table className="events-table" style={{ width: columns.width }}>
                {columns.colgroup}
                <thead>{columns.headings}</thead>
                <tbody>
                  {result.data.items.map((operation) => {
                    const event = operation.latest;
                    const outcome = operationOutcome(event.outcome);
                    const outcomeLabel =
                      outcome === "STARTED"
                        ? t("events.running")
                        : t("events.outcomes." + outcome);
                    const date = (value: string | null) =>
                      value
                        ? new Intl.DateTimeFormat(i18n.resolvedLanguage, {
                            dateStyle: "medium",
                            timeStyle: "medium",
                          }).format(new Date(value))
                        : "—";
                    return (
                      <tr key={event.id}>
                        {columns.cells([
                          <td>{date(operation.startedAt)}</td>,
                          <td>{date(operation.finishedAt)}</td>,
                          <td>
                            {operation.durationMs === null
                              ? "—"
                              : formatDuration(
                                  operation.durationMs,
                                  i18n.resolvedLanguage,
                                )}
                          </td>,
                          <td>
                            {filterValue(
                              "category",
                              event.category,
                              t("events.categories." + event.category),
                            )}
                          </td>,
                          <td>
                            {filterValue(
                              "action",
                              event.action,
                              t("events.actions." + event.action),
                            )}
                          </td>,
                          <td>
                            {filterValue(
                              "outcome",
                              outcome,
                              outcomeLabel,
                              <Badge
                                variant="light"
                                color={
                                  event.outcome === "FAILED"
                                    ? "red"
                                    : event.outcome === "PARTIAL"
                                      ? "orange"
                                      : undefined
                                }
                              >
                                {outcomeLabel}
                              </Badge>,
                            )}
                          </td>,
                          <td>
                            {filterValue(
                              "origin",
                              event.origin,
                              t("events.origins." + event.origin),
                            )}
                          </td>,
                          <td>{eventActorLabel(event, t)}</td>,
                          <td>
                            <details
                              open={expanded.includes(event.operationId)}
                              onToggle={(e) => {
                                const open = e.currentTarget.open;
                                setExpanded((current) =>
                                  open
                                    ? current.includes(event.operationId)
                                      ? current
                                      : [...current, event.operationId]
                                    : current.filter(
                                        (id) => id !== event.operationId,
                                      ),
                                );
                              }}
                            >
                              <summary>{t("events.details")}</summary>
                              <EventSummary operation={operation} />
                            </details>
                          </td>,
                        ])}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          {!operationId && (
            <Group mt="md">
              <Pagination
                value={page + 1}
                total={Math.max(1, Math.ceil(result.data.total / 20))}
                onChange={(value) => setPage(value - 1)}
              />
            </Group>
          )}
        </section>
      )}
    </>
  );
}
