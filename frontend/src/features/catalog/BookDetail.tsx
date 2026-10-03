import { BookCover } from "./BookCover";
import { BookActions } from "./BookActions";
import { useLocale } from "../../locales/useLocale";
import { useTranslation } from "react-i18next";
import { useBook } from "../../api/useBook";
import { Link, useParams, useLocation } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { catalogReturnUrl } from "./navigation";
import { Loading, Failure } from "../../components/Feedback";
export function BookDetail() {
  const { t } = useTranslation();
  const { number, date, language, status } = useLocale();
  const { id } = useParams();
  const location = useLocation();
  const result = useBook(id);
  const book = result.data;
  return (
    <>
      <Link className="back-link" to={catalogReturnUrl(location.state)}>
        <ArrowLeft size={16} />
        {t("detail.back")}{" "}
      </Link>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => result.refetch()} />
      ) : (
        book && (
          <>
            <div className="detail-header">
              <BookCover book={book} detail />
              <div className="detail-heading">
                <div className="eyebrow">
                  {t("detail.heading", { id: book.eplId })}
                </div>
                <h1>{book.title}</h1>
                <p className="author">{book.author}</p>
                <div className="tags">
                  <span className="badge">{language(book.language)}</span>
                  <span className="badge">
                    {t("detail.revision", {
                      revision:
                        book.revision == null ? "—" : String(book.revision),
                    })}
                  </span>
                  {book.genres && <span className="muted">{book.genres}</span>}
                </div>
              </div>
            </div>
            <BookActions key={book.eplId} book={book} />
            <div className="detail-grid">
              <section className="panel detail-section">
                <h2>{t("detail.synopsis")} </h2>
                <p className="synopsis">
                  {book.synopsis || t("detail.noSynopsis")}
                </p>
              </section>
              <section className="panel detail-section">
                <h2>{t("detail.info")} </h2>
                <dl>
                  {Object.entries({
                    publicationYear: number(book.publicationYear, {
                      useGrouping: false,
                    }),
                    pages: number(book.pages),
                    collection: book.collection,
                    volume: number(book.volume),
                    status: status(book.status),
                    publicationStatus: status(book.publicationStatus),
                    rating: number(book.rating),
                    votesCount: number(book.votesCount),
                    publicationDate: date(book.publicationDate),
                    insertDate: date(book.insertDate),
                    lastModifiedDate: date(book.lastModifiedDate),
                  }).map(([label, value]) => (
                    <div key={label}>
                      <dt>{t(`detail.${label}`)}</dt>
                      <dd>{value ?? "—"}</dd>
                    </div>
                  ))}
                </dl>
              </section>
            </div>
            <section className="panel detail-section">
              <h2>{t("detail.history")} </h2>
              <p className="muted">{t("detail.historyDescription")} </p>
              {book.download.items.length ? (
                <ul className="download-list">
                  {book.download.items.map((item) => (
                    <li key={item.id}>
                      <span>
                        {t("detail.revision", {
                          revision:
                            item.revision == null ? "—" : String(item.revision),
                        })}
                      </span>
                      <span className="badge">{status(item.status)}</span>
                      {item.completed && <span>{t("detail.completed")} </span>}
                    </li>
                  ))}
                </ul>
              ) : (
                <p>{t("detail.noDownloads")} </p>
              )}
            </section>
          </>
        )
      )}
    </>
  );
}
