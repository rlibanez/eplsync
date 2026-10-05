import { useAuth } from "../auth/Auth";
import { Synopsis } from "./Synopsis";
import { BookNavigation } from "./BookNavigation";
import { CatalogValue, quickFilter } from "./CatalogValue";
import type { Column } from "./CatalogColumns";
import { BookCover } from "./BookCover";
import { BookActions } from "./BookActions";
import { useLocale } from "../../locales/useLocale";
import { useTranslation } from "react-i18next";
import { useBook } from "../../api/useBook";
import { Link, useParams, useLocation, useNavigate } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { catalogReturnUrl } from "./navigation";
import { Loading, Failure } from "../../components/Feedback";
export function BookDetail() {
  const { t } = useTranslation();
  const auth = useAuth();
  const { number, date, status } = useLocale();
  const { id } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const onFilter = (column: Column, value: string) =>
    navigate(`/catalog?${quickFilter(new URLSearchParams(), column, value)}`);
  const result = useBook(id);
  const book = result.data;
  return (
    <>
      <div className="detail-navigation-row">
        <Link className="back-link" to={catalogReturnUrl(location.state)}>
          <ArrowLeft size={16} />
          {t("detail.back")}{" "}
        </Link>
        {book && <BookNavigation id={book.eplId} />}
      </div>
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
                <h1>
                  <button
                    className="catalog-value-filter"
                    onClick={() => onFilter("title", book.title)}
                  >
                    {book.title}
                  </button>
                </h1>
                <p className="author">
                  <CatalogValue
                    book={book}
                    column="author"
                    onFilter={onFilter}
                    separator=" · "
                  />
                </p>
                <div className="tags">
                  <span className="badge">EPL {book.eplId}</span>
                  <span className="badge">
                    {t("detail.revision", {
                      revision:
                        book.revision == null ? "—" : String(book.revision),
                    })}
                  </span>
                </div>
              </div>
            </div>
            <BookActions key={book.eplId} book={book} />
            <div className="detail-grid">
              <section className="panel detail-section">
                <h2>{t("detail.synopsis")} </h2>
                <Synopsis text={book.synopsis || t("detail.noSynopsis")} />
              </section>
              <section className="panel detail-section">
                <h2>{t("detail.info")} </h2>
                <dl>
                  {Object.entries({
                    collection: (
                      <CatalogValue
                        book={book}
                        column="collection"
                        onFilter={onFilter}
                      />
                    ),
                    volume: number(book.volume),
                    genres: (
                      <CatalogValue
                        book={book}
                        column="genres"
                        onFilter={onFilter}
                      />
                    ),
                    pages: (
                      <CatalogValue
                        book={book}
                        column="pages"
                        onFilter={onFilter}
                      />
                    ),
                    publicationYear: (
                      <CatalogValue
                        book={book}
                        column="publicationYear"
                        onFilter={onFilter}
                      />
                    ),
                    language: (
                      <CatalogValue
                        book={book}
                        column="language"
                        onFilter={onFilter}
                      />
                    ),
                    status: (
                      <CatalogValue
                        book={book}
                        column="status"
                        onFilter={onFilter}
                      />
                    ),
                    publicationStatus: (
                      <CatalogValue
                        book={book}
                        column="publicationStatus"
                        onFilter={onFilter}
                      />
                    ),
                    rating: number(book.rating),
                    votesCount: number(book.votesCount),
                    publicationDate: (
                      <CatalogValue
                        book={book}
                        column="publicationDate"
                        onFilter={onFilter}
                      />
                    ),
                    insertDate: (
                      <CatalogValue
                        book={book}
                        column="insertDate"
                        onFilter={onFilter}
                      />
                    ),
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
            {auth.can("BOOK_HISTORY_READ") && (
              <section className="panel detail-section">
                <h2>{t("detail.history")} </h2>
                {book.download.items.length ? (
                  <ul className="download-list">
                    {book.download.items.map((item) => (
                      <li key={item.id}>
                        <span>
                          {t("detail.revision", {
                            revision:
                              item.revision == null
                                ? "—"
                                : String(item.revision),
                          })}
                        </span>
                        <span className="badge">{status(item.status)}</span>
                        {item.completed && (
                          <span>{t("detail.completed")} </span>
                        )}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p>{t("detail.noDownloads")} </p>
                )}
              </section>
            )}
          </>
        )
      )}
    </>
  );
}
