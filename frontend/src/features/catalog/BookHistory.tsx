import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get, type Book } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, type Page } from "../downloads/shared";

export function BookHistory({ id }: { id: number }) {
  const { t } = useTranslation();
  const { status } = useLocale();
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20);
  const result = useQuery({
    queryKey: ["book-history", id, page, size],
    queryFn: ({ signal }) =>
      get<Page<Book["download"]["items"][number]>>(
        `/catalog/books/${id}/history?page=${page}&size=${size}`,
        signal,
      ),
  });
  return (
    <section className="panel detail-section">
      <h2>{t("detail.history")}</h2>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => result.refetch()} />
      ) : (
        <>
          {result.data.items.length ? (
            <ul className="download-list">
              {result.data.items.map((item) => (
                <li key={item.id}>
                  <span>
                    {t("detail.revision", {
                      revision:
                        item.revision == null ? "—" : String(item.revision),
                    })}
                  </span>
                  <span className="badge">{status(item.status)}</span>
                  {item.completed && <span>{t("detail.completed")}</span>}
                </li>
              ))}
            </ul>
          ) : (
            <p className="muted">{t("detail.noDownloads")}</p>
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
