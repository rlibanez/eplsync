import { useSyncSession } from "./useSyncSession";
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
  items: SyncItem[];
  ignoredTorrents: { hash: string; name: string | null; reason: string }[];
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
  const { view, setView } = useSyncSession();
  const { tab, action, outcome, search, page, size } = view;
  const setTab = (tab: string) => setView({ tab });
  const setAction = (action: string) => setView({ action });
  const setOutcome = (outcome: string) => setView({ outcome });
  const setSearch = (search: string) => setView({ search });
  const setPage = (page: number) => setView({ page });
  const setSize = (size: number) => setView({ size });
  const needle = search.trim().toLocaleLowerCase();
  const items = report.items.filter(
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
  );
  const ignored = report.ignoredTorrents.filter((item) =>
    `${item.name || ""} ${item.hash}`.toLocaleLowerCase().includes(needle),
  );
  const total = tab === "books" ? items.length : ignored.length;
  const totalPages = Math.ceil(total / size);
  const start = page * size;
  return (
    <section className="panel settings-section sync-report">
      <div className="sync-report-heading">
        <h2>{t("syncReport.title")}</h2>
        <Tooltip label={t("syncReport.close")}>
          <ActionIcon
            variant="subtle"
            aria-label={t("syncReport.close")}
            onClick={onClose}
          >
            <X size={20} />
          </ActionIcon>
        </Tooltip>
      </div>
      <p>
        <strong>
          {t(report.applied ? "syncReport.applied" : "syncReport.preview")}
        </strong>{" "}
        · {report.client} · {date(report.checkedAt)}
      </p>
      {(["remote", "records", "outcomes"] as const).map((group) => (
        <div key={group}>
          <h3>{t(`syncReport.${group}`)}</h3>
          <dl className="import-summary">
            {Object.entries(report[group]).map(([key, value]) => (
              <div key={key}>
                <dt>
                  {(
                    <Tooltip
                      label={t(`syncReport.explanations.${key}`)}
                      multiline
                      w={300}
                    >
                      <span tabIndex={0} className="sync-count-help">
                        {t(`syncReport.counts.${key}`)}
                      </span>
                    </Tooltip>
                  )}
                </dt>
                <dd>{number(value)}</dd>
              </div>
            ))}
          </dl>
        </div>
      ))}
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
          value={search}
          onChange={(event) => {
            setSearch(event.currentTarget.value);
            setPage(0);
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
                (value) => ({ value, label: t(`syncReport.actions.${value}`) }),
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
              data={["ALL", "newlyCompleted", "notFound", "newlyNotFound"].map(
                (value) => ({
                  value,
                  label:
                    value === "ALL"
                      ? t("syncReport.actions.ALL")
                      : t(`syncReport.counts.${value}`),
                }),
              )}
            />
          </>
        )}
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              {(tab === "books"
                ? ["book", "action", "previous", "result", "changes"]
                : ["name", "hash", "reason"]
              ).map((key) => (
                <th key={key}>{t(`syncReport.${key}`)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {tab === "books"
              ? items.slice(start, start + size).map((item) => (
                  <tr key={`${item.eplId}:${item.hash}`}>
                    <td>
                      <Link
                        className="book-title"
                        to={`/catalog/${item.eplId}`}
                      >
                        {item.title || `EPL ${item.eplId}`}
                      </Link>
                      <small className="hash-text">
                        EPL {item.eplId} · {item.hash}
                      </small>
                    </td>
                    <td>{t(`syncReport.actions.${item.action}`)}</td>
                    <td>
                      {item.previousStatus ? status(item.previousStatus) : "—"}
                    </td>
                    <td>{status(item.resultingStatus)}</td>
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
                    </td>
                  </tr>
                ))
              : ignored.slice(start, start + size).map((item) => (
                  <tr key={item.hash}>
                    <td>{item.name || "—"}</td>
                    <td className="sync-hash">{item.hash}</td>
                    <td>
                      {t(`syncReport.reasons.${item.reason}`, {
                        defaultValue: item.reason,
                      })}
                    </td>
                  </tr>
                ))}
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
    </section>
  );
}
