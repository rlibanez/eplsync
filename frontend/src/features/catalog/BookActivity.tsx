import { useEffect, useState } from "react";
import { Badge } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Loading, Failure } from "../../components/Feedback";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { type Permission } from "../auth/Auth";
import { useLocale } from "../../locales/useLocale";
import { useDownloadColumns } from "../downloads/DownloadTable";
import { sortQuery } from "../downloads/TableControls";
import { Paging, type Page } from "../downloads/shared";

type Entry = {
  id: string;
  createdAt: string | null;
  operation: string;
  revision: string | null;
  result: string;
  message: string | null;
  href: string | null;
};
export function canReadBookActivity(can: (permission: Permission) => boolean) {
  return (
    [
      "BOOK_HISTORY_READ",
      "TORRENT_JOBS_MANAGE",
      "TORRENT_SYNC",
      "CALIBRE_READ",
      "CALIBRE_SEND",
      "CALIBRE_JOBS_MANAGE",
    ] as const
  ).some(can);
}
export function BookActivity({ id }: { id: number }) {
  const { t } = useTranslation();
  const { date, revision } = useLocale();
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20);
  const [sort, setSort] = useState("createdAt,desc");
  const choice = usePageSizeChoice(size);
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  const query = useQuery({
    queryKey: ["book-activity", id, params.toString(), sort],
    queryFn: ({ signal }) =>
      get<Page<Entry>>(
        `/catalog/books/${id}/activity?${params}&${sortQuery(sort)}`,
        signal,
      ),
    refetchInterval: (query) =>
      query.state.data?.items.some((row) =>
        ["PENDING", "RUNNING", "UNCERTAIN"].includes(row.result),
      )
        ? 10000
        : false,
  });
  useEffect(() => {
    if (query.data)
      setPage((current) =>
        Math.min(current, Math.max(0, query.data.meta.totalPages - 1)),
      );
  }, [query.data]);
  const columns = useDownloadColumns(
    "eplsync.book-activity.columns",
    [
      { field: "createdAt", label: "bookActivity.date", width: 200 },
      { field: "operation", label: "bookActivity.operation", width: 240 },
      { field: "revision", label: "downloads.revision", width: 110 },
      { field: "result", label: "bookActivity.result", width: 160 },
      { field: "message", label: "calibreSend.detail", width: 420 },
      { field: "job", label: "downloads.job", width: 180, sortable: false },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
    },
    { defaults: "createdAt,desc" },
    (page + 1) * size,
  );
  return (
    <section
      className="panel book-activity"
      aria-label={t("bookActivity.title")}
    >
      <div className="book-section-heading">
        <h2>{t("bookActivity.title")}</h2>
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <Failure error={query.error} retry={() => query.refetch()} />
      ) : query.data.meta.totalItems === 0 ? null : (
        <>
          <div className="table-toolbar">
            <div className="catalog-table-controls">{columns.controls}</div>
          </div>
          <div className="table-scroll">
            <table
              className="download-record-table"
              style={{ width: columns.width }}
            >
              {columns.colgroup}
              <caption className="sr-only">{t("bookActivity.title")}</caption>
              <thead>{columns.headings}</thead>
              <tbody>
                {query.data.items.length ? (
                  query.data.items.map((row, index) => (
                    <tr key={row.id}>
                      {columns.cells(
                        [
                          <td>{date(row.createdAt)}</td>,
                          <td>
                            {t(`bookActivity.operations.${row.operation}`)}
                          </td>,
                          <td>
                            {revision(
                              row.revision == null
                                ? null
                                : Number(row.revision),
                            )}
                          </td>,
                          <td>
                            <Badge
                              color={
                                row.result === "SUCCEEDED"
                                  ? "green"
                                  : row.result === "FAILED"
                                    ? "red"
                                    : [
                                          "BLOCKED",
                                          "UNCERTAIN",
                                          "PAUSED",
                                        ].includes(row.result)
                                      ? "orange"
                                      : "gray"
                              }
                            >
                              {t(`bookActivity.results.${row.result}`)}
                            </Badge>
                          </td>,
                          <td className="job-item-message">
                            {row.message || "—"}
                          </td>,
                          <td>
                            {row.href ? (
                              <Link to={row.href}>
                                {t("bookActivity.openJob")}
                              </Link>
                            ) : (
                              "—"
                            )}
                          </td>,
                        ],
                        page * size + index + 1,
                      )}
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={columns.visible.length}>
                      <p className="empty-list">{t("bookActivity.empty")}</p>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <Paging
            choice={choice}
            meta={query.data.meta}
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
