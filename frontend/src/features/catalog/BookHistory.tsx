import { HistorySelection } from "./HistorySelection";
import { useAuth } from "../auth/Auth";
import { Checkbox } from "@mantine/core";
import { useDownloadColumns } from "../downloads/DownloadTable";
import { sortQuery } from "../downloads/TableControls";
import type { Download } from "../downloads/DownloadState";
import { useState, useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, type Page } from "../downloads/shared";

type HistoryItem = Pick<
  Download,
  | "id"
  | "revision"
  | "hash"
  | "status"
  | "client"
  | "clientInstanceId"
  | "origin"
  | "lastCheckedAt"
  | "completedAt"
  | "lastError"
>;

export function BookHistory({ id }: { id: number }) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const selectable = can("TORRENT_SYNC") || can("TORRENT_CLEANUP");
  const [selected, setSelected] = useState<string[]>([]);
  const { status, number, date } = useLocale();
  const [sort, setSort] = useState("revision,desc");
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20);
  const columns = useDownloadColumns(
    "eplsync.history.columns",
    [
      { field: "hash", label: "downloads.hash", width: 360 },
      { field: "revision", label: "downloads.revision", width: 100 },
      { field: "status", label: "downloads.status", width: 160 },
      { field: "client", label: "downloads.client", width: 160 },
      { field: "lastCheckedAt", label: "downloads.lastChecked", width: 200 },
      { field: "completedAt", label: "downloads.completed", width: 220 },
      { field: "lastError", label: "downloads.error", width: 250 },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
    },
    { defaults: "revision,desc", selection: selectable },
  );
  const result = useQuery({
    queryKey: ["book-history", id, page, size, sort],
    queryFn: ({ signal }) =>
      get<Page<HistoryItem>>(
        `/catalog/books/${id}/history?page=${page}&size=${size}&${sortQuery(sort)}`,
        signal,
      ),
  });
  useEffect(() => setSelected([]), [page, size, sort, id]);
  return (
    <section className="panel book-history">
      <div className="table-toolbar book-history-toolbar">
        <h2>{t("detail.history")}</h2>
        <div className="catalog-table-controls">{columns.controls}</div>
      </div>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => result.refetch()} />
      ) : (
        <>
          <HistorySelection
            eplId={id}
            selected={selected}
            rows={result.data.items}
            onDone={() => setSelected([])}
          />
          <div className="table-scroll">
            <table
              className="download-record-table"
              style={{ width: columns.width }}
            >
              {columns.colgroup}
              <caption className="sr-only">{t("detail.history")}</caption>
              <thead>
                {columns.selectionHeadings(
                  selectable && (
                    <th className="history-selection">
                      <Checkbox
                        aria-label={t("historyActions.selectPage")}
                        checked={
                          result.data.items.length > 0 &&
                          result.data.items.every((r) =>
                            selected.includes(r.id),
                          )
                        }
                        indeterminate={
                          selected.length > 0 &&
                          !result.data.items.every((r) =>
                            selected.includes(r.id),
                          )
                        }
                        disabled={!result.data.items.length}
                        onChange={(e) =>
                          setSelected(
                            e.currentTarget.checked
                              ? result.data.items.map((r) => r.id)
                              : [],
                          )
                        }
                      />
                    </th>
                  ),
                )}
              </thead>
              <tbody>
                {result.data.items.map((item) => (
                  <tr key={item.id}>
                    {selectable && (
                      <td className="history-selection">
                        <Checkbox
                          aria-label={t("historyActions.selectRevision", {
                            revision: number(item.revision),
                          })}
                          checked={selected.includes(item.id)}
                          onChange={(e) => {
                            const checked = e.currentTarget.checked;
                            setSelected((previous) =>
                              checked
                                ? [...previous, item.id]
                                : previous.filter((id) => id !== item.id),
                            );
                          }}
                        />
                      </td>
                    )}
                    {columns.cells([
                      <td>
                        <small className="hash-text" title={item.hash}>
                          {item.hash || "—"}
                        </small>
                      </td>,
                      <td>{number(item.revision)}</td>,
                      <td>
                        <span className="badge">{status(item.status)}</span>
                      </td>,
                      <td title={item.clientInstanceId}>
                        {item.client || "—"}
                        <small className="hash-text">{item.origin}</small>
                      </td>,
                      <td>{date(item.lastCheckedAt)}</td>,
                      <td>{date(item.completedAt)}</td>,
                      <td>{item.lastError || "—"}</td>,
                    ])}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!result.data.items.length && (
            <p className="empty-list book-history-empty">
              {t("detail.noDownloads")}
            </p>
          )}
          <Paging
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
  );
}
