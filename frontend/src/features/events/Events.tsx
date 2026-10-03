import { EventSummary } from "./EventSummary";
import { readEventNavigation, saveEventNavigation } from "./eventNavigation";
import { useEventColumns } from "./EventColumns";
import { markEventsRead, markOperationRead } from "./useUnreadEvents";
import { useEffect, useState, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
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
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Failure, Loading } from "../../components/Feedback";
import {
  dateBounds,
  eventHref,
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
  const [saved] = useState(() => operationId ? {
    category: null, outcome: null, origin: null, from: "", to: "", page: 0,
    expanded: [operationId], scroll: 0,
  } : readEventNavigation());
  const [visit] = useState(() => Math.random());
  const snapshot = useRef<number | undefined>(undefined);
  const restored = useRef(false);
  const cache = useQueryClient();
  const columns = useEventColumns();
  const [visible, setVisible] = useState(
    document.visibilityState === "visible",
  );
  useEffect(() => {
    const update = () => setVisible(document.visibilityState === "visible");
    document.addEventListener("visibilitychange", update);
    return () => document.removeEventListener("visibilitychange", update);
  }, []);
  const { t, i18n } = useTranslation();
  const [category, setCategory] = useState<string | null>(saved.category);
  const [outcome, setOutcome] = useState<string | null>(saved.outcome);
  const [origin, setOrigin] = useState<string | null>(saved.origin);
  const [from, setFrom] = useState(saved.from);
  const [to, setTo] = useState(saved.to);
  const [page, setPage] = useState(saved.page);
  const [expanded, setExpanded] = useState(saved.expanded);
  const navigation = useRef(saved);
  navigation.current = {
    category,
    outcome,
    origin,
    from,
    to,
    page,
    expanded,
    scroll: navigation.current.scroll,
  };
  useEffect(() => {
    if (!operationId) saveEventNavigation(navigation.current);
  }, [category, outcome, origin, from, to, page, expanded, operationId]);
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
  if (operationId) params.set("operationId", operationId);
  if (category) params.set("category", category);
  if (outcome) params.set("outcome", outcome);
  if (origin) params.set("origin", origin);
  const bounds = dateBounds(from, to);
  if (bounds.from) params.set("from", bounds.from);
  if (bounds.before) params.set("before", bounds.before);
  const result = useQuery({
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
        const selected = result.data.items.find(item => item.latest.operationId === operationId);
        if (selected) markOperationRead(selected.latest.id);
      } else markEventsRead(result.data.cursor);
    }
    if (result.data)
      setPage((current) =>
        Math.min(current, Math.max(0, Math.ceil(result.data.total / 20) - 1)),
      );
  }, [result.data, result.isFetching, visible, operationId]);
  const filterValue = (
    field: "category" | "outcome" | "origin",
    value: string,
    label: string,
    content = <>{label}</>,
  ) => {
    const help = t("catalog.filterByValue", {
      field: t("events." + field),
      value: label,
    });
    return (
      <Tooltip label={help}>
        <button
          type="button"
          className="catalog-value-filter"
          aria-label={help}
          onClick={() => {
            ({ category: setCategory, outcome: setOutcome, origin: setOrigin })[
              field
            ](value);
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
      </div>
      <p className="muted">{t("events.description")}</p>
      {operationId ? (
        <Button component={Link} to="/events" variant="subtle" mb="md">
          {t("events.showAll")}
        </Button>
      ) : <div className="filters">
        <Select
          label={t("events.category")}
          clearable
          value={category}
          data={["CATALOG", "JOB", "COVERS", "TORRENT"].map((value) => ({
            value,
            label: t("events.categories." + value),
          }))}
          onChange={(value) => {
            setCategory(value);
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
        <Button
          variant="subtle"
          onClick={() => {
            setCategory(null);
            setOutcome(null);
            setOrigin(null);
            setFrom("");
            setTo("");
            setPage(0);
          }}
        >
          {t("catalog.clear")}
        </Button>
      </div>}
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : (
        <section className="panel events-panel">
          <Group className="events-table-heading" justify="space-between">
            <span>
              {t("events.operationsTotal", { count: result.data.total })}
            </span>
            <Button
              size="xs"
              variant="light"
              onClick={refresh}
              loading={result.isFetching}
              style={{ visibility: pending ? "visible" : "hidden" }}
              disabled={!pending}
              aria-hidden={!pending}
              tabIndex={pending ? 0 : -1}
              title={t("events.newEvents")}
            >
              {t("events.refresh")}
            </Button>
            <span className="sr-only" role="status">
              {pending ? t("events.newEvents") : ""}
            </span>
          </Group>
          {!result.data.items.length ? (
            <p>{t(operationId ? "events.missingOperation" : "events.empty")}</p>
          ) : (
            <div className="table-scroll">
              <table className="events-table" style={{ width: columns.width }}>
                {columns.colgroup}
                <thead>
                  <tr>{columns.headers}</tr>
                </thead>
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
                        <td>{date(operation.startedAt)}</td>
                        <td>{date(operation.finishedAt)}</td>
                        <td>
                          {operation.durationMs === null
                            ? "—"
                            : formatDuration(
                                operation.durationMs,
                                i18n.resolvedLanguage,
                              )}
                        </td>
                        <td>
                          {filterValue(
                            "category",
                            event.category,
                            t("events.categories." + event.category),
                          )}
                        </td>
                        <td>
                          <Link to={eventHref(event)}>
                            {t("events.actions." + event.action)}
                          </Link>
                        </td>
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
                        </td>
                        <td>
                          {filterValue(
                            "origin",
                            event.origin,
                            t("events.origins." + event.origin),
                          )}
                        </td>
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
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          {!operationId && <Group mt="md">
            <Pagination
              value={page + 1}
              total={Math.max(1, Math.ceil(result.data.total / 20))}
              onChange={(value) => setPage(value - 1)}
            />
          </Group>}
        </section>
      )}
    </>
  );
}
