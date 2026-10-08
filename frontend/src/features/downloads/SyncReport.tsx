import { DownloadBook, useDownloadColumns } from "./DownloadTable";
import { useQuery } from "@tanstack/react-query";
import { get } from "../../api/catalog";
import { ordering } from "./TableControls";
import { LinkTorrent } from "./LinkTorrent";
import { useEffect, useRef, useState } from "react";
import { defaultSyncView, useSyncSession } from "./useSyncSession";
import { X } from "lucide-react";
import { ActionIcon, Button, Select, TextInput, Tooltip } from "@mantine/core";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useLocale } from "../../locales/useLocale";
import { Paging } from "./shared";
export interface SyncItem {
  downloadId: string | null;
  eplId: number;
  title: string | null;
  hash: string;
  coverUrl?: string | null;
  coverAvailable?: boolean | null;
  action: "CREATE" | "UPDATE" | "UNCHANGED";
  previousStatus: string | null;
  resultingStatus: string;
  foundInClient: boolean;
  changedFields: string[];
  newlyCompleted: boolean;
  newlyNotFound: boolean;
  previousCompletedAt: string | null;
  resultingCompletedAt: string | null;
  previousError: string | null;
  resultingError: string | null;
}
export interface SyncResult {
  cleanup?: {
    id: string;
    downloadId: string;
    eplId: number;
    hash: string;
    policy: string;
    state: string;
    message: string;
  }[];
  detailsId?: string | null;
  dryRun: boolean;
  applied: boolean;
  checkedAt: string;
  client: string;
  clientInstanceId: string;
  remote: { total: number; matched: number; ignored: number };
  records: {
    checked: number;
    created: number;
    updated: number;
    unchanged: number;
  };
  outcomes: { newlyCompleted: number; notFound: number; newlyNotFound: number };
  items?: SyncItem[] | null;
  ignoredTorrents?:
    | {
        hash: string;
        name: string | null;
        linked?: boolean;
        reason: string;
      }[]
    | null;
}
export function SyncReport({
  report,
  onClose,
}: {
  report: SyncResult;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { date, number, status } = useLocale();
  const { view, setView, markLinked, linkedHashes } = useSyncSession();
  const [linking, setLinking] = useState<
    NonNullable<SyncResult["ignoredTorrents"]>[number] | null
  >(null);
  const { tab, action, outcome, search, page, size } = view;
  const setTab = (tab: string) => setView({ tab });
  const setAction = (action: string) => setView({ action });
  const setOutcome = (outcome: string) => setView({ outcome });
  const [searchText, setSearchText] = useState(search);
  const composing = useRef(false);
  useEffect(() => {
    setSearchText(view.search);
  }, [report, view.search]);
  const setSearch = (search: string) => {
    setSearchText(search);
    setView({ search, page: 0 });
  };
  const setPage = (page: number) => setView({ page });
  const setSize = (size: number) => setView({ size });
  const bookColumns = useDownloadColumns(
    "eplsync.sync.books.columns",
    [
      { field: "eplId", label: "filters.eplId", width: 100 },
      { field: "title", label: "downloads.book", width: 360 },
      { field: "hash", label: "downloads.hash", width: 360 },
      { field: "action", label: "syncReport.action", width: 150 },
      { field: "previousStatus", label: "syncReport.previous", width: 180 },
      { field: "resultingStatus", label: "syncReport.result", width: 180 },
      { field: "changes", label: "syncReport.changes", width: 300 },
    ],
    view.bookSort,
    (value) => setView({ bookSort: value, page: 0 }),
    { defaults: "eplId,asc" },
  );
  const ignoredColumns = useDownloadColumns(
    "eplsync.sync.ignored.columns",
    [
      { field: "name", label: "syncReport.name", width: 360 },
      { field: "hash", label: "downloads.hash", width: 360 },
      { field: "reason", label: "syncReport.reason", width: 260 },
      {
        field: "actions",
        label: "torrentLink.actions",
        width: 170,
        sortable: false,
      },
    ],
    view.ignoredSort,
    (value) => setView({ ignoredSort: value, page: 0 }),
    { defaults: "name,asc" },
  );
  const columns = tab === "books" ? bookColumns : ignoredColumns;
  function sorted<T>(
    rows: T[],
    sort: string,
    value: (row: T, field: string) => string | number | null | undefined,
  ) {
    const collator = new Intl.Collator(undefined, {
      numeric: true,
      sensitivity: "base",
    });
    return [...rows].sort((left, right) => {
      for (const criterion of ordering(sort)) {
        const [field, direction] = criterion.split(",");
        const a = value(left, field),
          b = value(right, field);
        const comparison =
          typeof a === "number" && typeof b === "number"
            ? a - b
            : collator.compare(String(a ?? ""), String(b ?? ""));
        if (comparison) return direction === "desc" ? -comparison : comparison;
      }
      return 0;
    });
  }
  const needle = search.trim().toLocaleLowerCase();
  const items = sorted(
    (report.items || []).filter(
      (item) =>
        (action === "ALL" ||
          (action === "CHANGED"
            ? item.action !== "UNCHANGED"
            : item.action === action)) &&
        (outcome === "ALL" ||
          (outcome === "notFound"
            ? item.resultingStatus === "NOT_FOUND"
            : item[outcome as "newlyCompleted" | "newlyNotFound"])) &&
        `${item.title || ""} ${item.eplId} ${item.hash}`
          .toLocaleLowerCase()
          .includes(needle),
    ),
    view.bookSort,
    (item, field) => {
      if (field === "action") return t(`syncReport.actions.${item.action}`);
      if (field === "previousStatus")
        return item.previousStatus ? status(item.previousStatus) : null;
      if (field === "resultingStatus") return status(item.resultingStatus);
      if (field === "changes")
        return item.changedFields
          .map((field) => t(`syncReport.fields.${field}`))
          .join(", ");
      return item[field as "title" | "eplId" | "hash"];
    },
  );
  const ignored = sorted(
    (report.ignoredTorrents || []).filter((item) =>
      `${item.name || ""} ${item.hash}`.toLocaleLowerCase().includes(needle),
    ),
    view.ignoredSort,
    (item, field) =>
      field === "reason"
        ? item.linked
          ? t("torrentLink.linked")
          : t(`syncReport.reasons.${item.reason}`, {
              defaultValue: item.reason,
            })
        : item[field as "name" | "hash"],
  );
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
    search,
    action,
    outcome,
  });
  params.set(
    "sort",
    ordering(tab === "books" ? view.bookSort : view.ignoredSort)
      .map((value) => value.replace("changes,", "changedFields,"))
      .join(";"),
  );
  const details = useQuery<{
    items: (SyncItem | NonNullable<SyncResult["ignoredTorrents"]>[number])[];
    meta: { totalItems: number };
  }>({
    queryKey: ["sync-report-details", report.detailsId, tab, params.toString()],
    queryFn: ({ signal }) =>
      get(
        `/torrent/downloads/reports/${report.detailsId}/${tab === "books" ? "books" : "ignored"}?${params}`,
        signal,
      ),
    enabled: !!report.detailsId,
    gcTime: 0,
  });
  const total = report.detailsId
    ? details.data?.meta.totalItems || 0
    : tab === "books"
      ? items.length
      : ignored.length;
  const totalPages = Math.ceil(total / size);
  const start = page * size;
  const visibleItems = report.detailsId
    ? ((details.data?.items || []) as SyncItem[])
    : items.slice(start, start + size);
  const visibleIgnored = report.detailsId
    ? ((details.data?.items || []) as NonNullable<
        SyncResult["ignoredTorrents"]
      >)
    : ignored.slice(start, start + size);
  return (
    <section className="panel settings-section sync-report">
      {linking && (
        <LinkTorrent
          torrent={linking}
          clientInstanceId={report.clientInstanceId}
          onClose={() => setLinking(null)}
          onLinked={() => markLinked(linking.hash)}
        />
      )}
      <Tooltip label={t("syncReport.close")}>
        <ActionIcon
          className="report-close"
          variant="subtle"
          aria-label={t("syncReport.close")}
          onClick={onClose}
        >
          <X size={20} />
        </ActionIcon>
      </Tooltip>
      <details className="report-collapse" key={report.checkedAt}>
        <summary>
          <span>{t("syncReport.title")}</span>
          <strong className="report-result">
            {t(report.applied ? "syncReport.applied" : "syncReport.preview")}
          </strong>
        </summary>
        <div className="report-collapse-body">
          <p>
            {report.client} · {date(report.checkedAt)}
          </p>
          {report.cleanup && <p>{t("syncReport.phases")}</p>}
          <div className="job-report-groups sync-report-groups">
            {(["remote", "records", "outcomes"] as const).map((group) => (
              <section className="job-report-group" key={group}>
                <h3>{t(`syncReport.${group}`)}</h3>
                <dl className="import-summary">
                  {Object.entries(report[group]).map(([key, value]) => (
                    <div key={key}>
                      <dt>
                        {
                          <Tooltip
                            label={t(`syncReport.explanations.${key}`)}
                            multiline
                            w={300}
                          >
                            <span tabIndex={0} className="sync-count-help">
                              {t(`syncReport.counts.${key}`)}
                            </span>
                          </Tooltip>
                        }
                      </dt>
                      <dd>
                        <Tooltip
                          label={t(`syncReport.explanations.${key}`)}
                          multiline
                          w={300}
                        >
                          <span tabIndex={0} className="sync-count-help">
                            {number(value)}
                          </span>
                        </Tooltip>
                      </dd>
                    </div>
                  ))}
                </dl>
              </section>
            ))}
            {report.cleanup && (
              <section className="job-report-group">
                <h3>{t("syncReport.cleanupTitle")}</h3>
                <ul>
                  {report.cleanup.map((entry) => (
                    <li key={entry.id}>
                      <Link to={`/catalog/${entry.eplId}`}>
                        EPL {entry.eplId}
                      </Link>{" "}
                      · {entry.hash}: {entry.message}
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </div>
          <div
            className="action-row"
            role="group"
            aria-label={t("syncReport.details")}
          >
            {["books", "ignored"].map((key) => (
              <Button
                key={key}
                variant={tab === key ? "filled" : "default"}
                aria-pressed={tab === key}
                onClick={() => {
                  setTab(key);
                  setPage(0);
                  setSearch("");
                }}
              >
                {t(`syncReport.${key}`)}
              </Button>
            ))}
          </div>
          <div className="filters">
            <TextInput
              label={t("catalog.search")}
              value={searchText}
              onCompositionStart={() => {
                composing.current = true;
              }}
              onCompositionEnd={(event) => {
                composing.current = false;
                setSearch(event.currentTarget.value);
              }}
              onChange={(event) => {
                const value = event.currentTarget.value;
                setSearchText(value);
                if (!composing.current) setView({ search: value, page: 0 });
              }}
            />
            {tab === "books" && (
              <>
                <Select
                  label={t("syncReport.action")}
                  value={action}
                  allowDeselect={false}
                  onChange={(v) => {
                    setAction(v!);
                    setPage(0);
                  }}
                  data={["CHANGED", "ALL", "CREATE", "UPDATE", "UNCHANGED"].map(
                    (value) => ({
                      value,
                      label: t(`syncReport.actions.${value}`),
                    }),
                  )}
                />
                <Select
                  label={t("syncReport.outcome")}
                  value={outcome}
                  allowDeselect={false}
                  onChange={(v) => {
                    setOutcome(v!);
                    if (v !== "ALL") setAction("ALL");
                    setPage(0);
                  }}
                  data={[
                    "ALL",
                    "newlyCompleted",
                    "notFound",
                    "newlyNotFound",
                  ].map((value) => ({
                    value,
                    label:
                      value === "ALL"
                        ? t("syncReport.actions.ALL")
                        : t(`syncReport.counts.${value}`),
                  }))}
                />
              </>
            )}
            <Button
              variant="default"
              onClick={() => {
                setSearchText("");
                setView({ ...defaultSyncView, tab });
              }}
            >
              {t("catalog.clear")}
            </Button>
          </div>
          {details.error && <p role="alert">{String(details.error)}</p>}
          <div className="table-toolbar">
            <div className="catalog-table-controls">{columns.controls}</div>
          </div>
          <div className="table-scroll">
            <table
              className="download-record-table"
              style={{ width: columns.width }}
            >
              {columns.colgroup}
              <thead>{columns.headings}</thead>
              <tbody>
                {tab === "books"
                  ? visibleItems.map((item) => (
                      <tr key={`${item.eplId}:${item.hash}`}>
                        {bookColumns.cells([
                          <td>
                            <Link to={`/catalog/${item.eplId}`}>
                              {item.eplId}
                            </Link>
                          </td>,
                          <td>
                            <DownloadBook book={item} />
                          </td>,
                          <td className="hash-text">{item.hash}</td>,
                          <td>{t(`syncReport.actions.${item.action}`)}</td>,
                          <td>
                            {item.previousStatus
                              ? status(item.previousStatus)
                              : "—"}
                          </td>,
                          <td>{status(item.resultingStatus)}</td>,
                          <td>
                            {item.changedFields
                              .map((field) => t(`syncReport.fields.${field}`))
                              .join(", ")}
                            {!item.changedFields.length &&
                              !item.newlyCompleted &&
                              !item.newlyNotFound &&
                              "—"}
                            {item.newlyCompleted && (
                              <div>
                                {t("syncReport.completionDetected")}:{" "}
                                {date(item.resultingCompletedAt)}
                              </div>
                            )}
                            {item.newlyNotFound && (
                              <div>{t("syncReport.counts.newlyNotFound")}</div>
                            )}
                            {item.changedFields.includes("lastError") && (
                              <div>
                                {item.previousError || "—"} →{" "}
                                {item.resultingError || "—"}
                              </div>
                            )}
                          </td>,
                        ])}
                      </tr>
                    ))
                  : visibleIgnored.map((original) => {
                      const item = {
                        ...original,
                        linked:
                          original.linked ||
                          linkedHashes.includes(original.hash),
                      };
                      return (
                        <tr key={item.hash}>
                          {ignoredColumns.cells([
                            <td>{item.name || "—"}</td>,
                            <td className="sync-hash">{item.hash}</td>,
                            <td>
                              {item.linked
                                ? t("torrentLink.linked")
                                : t(`syncReport.reasons.${item.reason}`, {
                                    defaultValue: item.reason,
                                  })}
                            </td>,
                            <td>
                              <Button
                                variant="light"
                                disabled={item.linked}
                                onClick={() => setLinking(item)}
                              >
                                {t(
                                  item.linked
                                    ? "torrentLink.linked"
                                    : "torrentLink.link",
                                )}
                              </Button>
                            </td>,
                          ])}
                        </tr>
                      );
                    })}
              </tbody>
            </table>
          </div>
          {total === 0 && <p className="empty-list">{t("syncReport.empty")}</p>}
          <Paging
            page={page}
            size={size}
            onPage={setPage}
            onSize={(value) => {
              setSize(value);
              setPage(0);
            }}
            meta={{
              page,
              size,
              totalItems: total,
              totalPages,
              hasNext: page + 1 < totalPages,
              hasPrevious: page > 0,
            }}
          />
        </div>
      </details>
    </section>
  );
}
