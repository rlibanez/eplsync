import { ArrowDown, ArrowUp } from "lucide-react";
import type { Download } from "../downloads/DownloadState";
import { useState } from "react";
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
  const { status, number, date } = useLocale();
  const [sort, setSort] = useState("revision,desc");
  const columns = [
    ["hash", "hash"],
    ["revision", "revision"],
    ["status", "status"],
    ["client", "client"],
    ["lastCheckedAt", "lastChecked"],
    ["completedAt", "completed"],
    ["lastError", "error"],
  ] as const;
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20);
  const result = useQuery({
    queryKey: ["book-history", id, page, size, sort],
    queryFn: ({ signal }) =>
      get<Page<HistoryItem>>(
        `/catalog/books/${id}/history?page=${page}&size=${size}&sort=${encodeURIComponent(sort)}`,
        signal,
      ),
  });
  return (
    <section className="panel book-history">
      <div className="table-toolbar book-history-toolbar">
        <h2>{t("detail.history")}</h2>
      </div>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => result.refetch()} />
      ) : (
        <>
          <div className="table-scroll">
            <table>
              <caption className="sr-only">{t("detail.history")}</caption>
              <thead>
                <tr>
                  {columns.map(([field, label]) => {
                    const ascending = sort === `${field},asc`;
                    const active = sort.startsWith(field + ",");
                    return (
                      <th
                        key={field}
                        scope="col"
                        aria-sort={
                          active
                            ? ascending
                              ? "ascending"
                              : "descending"
                            : undefined
                        }
                      >
                        <button
                          type="button"
                          className="catalog-sort-heading"
                          onClick={() => {
                            setSort(`${field},${ascending ? "desc" : "asc"}`);
                            setPage(0);
                          }}
                        >
                          {t(`downloads.${label}`)}
                          {active &&
                            (ascending ? (
                              <ArrowUp size={14} aria-hidden="true" />
                            ) : (
                              <ArrowDown size={14} aria-hidden="true" />
                            ))}
                        </button>
                      </th>
                    );
                  })}
                </tr>
              </thead>
              <tbody>
                {result.data.items.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <small className="hash-text" title={item.hash}>
                        {item.hash || "—"}
                      </small>
                    </td>
                    <td>{number(item.revision)}</td>
                    <td>
                      <span className="badge">{status(item.status)}</span>
                    </td>
                    <td title={item.clientInstanceId}>
                      {item.client || "—"}
                      <small className="hash-text">{item.origin}</small>
                    </td>
                    <td>{date(item.lastCheckedAt)}</td>
                    <td>{date(item.completedAt)}</td>
                    <td>{item.lastError || "—"}</td>
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
