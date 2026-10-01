import { CatalogFilters, filterKeys, filterRequest } from "./CatalogFilters";
import { BookCover } from "./BookCover";
import { PageJump } from "../../components/PageJump";
import { useEffect } from "react";
import { rememberCatalog } from "./navigation";
import { useLocale } from "../../locales/useLocale";
import { useTranslation } from "react-i18next";
import { useCatalogScroll } from "./useCatalogScroll";
import { useQuery } from "@tanstack/react-query";
import { Button, Select } from "@mantine/core";
import { Link, useSearchParams } from "react-router-dom";
import { ArrowUpRight, BookOpen } from "lucide-react";
import { get, catalogParams, sorts, type BookPage } from "../../api/catalog";
import { Loading, Failure } from "../../components/Feedback";
export function Catalog() {
  const { t } = useTranslation();
  const { number, language } = useLocale();
  const [search, setSearch] = useSearchParams();
  useEffect(() => {
    rememberCatalog(search.toString());
  }, [search]);
  const params = catalogParams(search);
  const query = params.toString();
  const result = useQuery({
    queryKey: ["catalog", query],
    queryFn: ({ signal }) =>
      get<BookPage>(`/catalog/books?${filterRequest(params)}`, signal),
  });
  const rememberScroll = useCatalogScroll(query, result.isSuccess);
  function change(key: string, value: string) {
    const next = new URLSearchParams(params);
    next.delete("sort");
    next.set("sort", params.get("sort")!);
    next.set(key, value);
    if (key !== "page") next.set("page", "0");
    setSearch(next);
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>{t("nav.catalog")} </h1>
        </div>
        <span className="view-label">
          <BookOpen size={17} />
          {t("catalog.table")}{" "}
        </span>
      </div>
      <CatalogFilters
        key={search.toString()}
        params={params}
        onChange={setSearch}
      />
      <section className="panel">
        <div className="table-toolbar">
          <span aria-live="polite">
            {result.data
              ? t("catalog.results", {
                  count: result.data.meta.totalItems,
                  formattedCount: number(result.data.meta.totalItems),
                })
              : t("catalog.books")}
          </span>
          <Select
            aria-label={t("catalog.sort")}
            value={params.get("sort")}
            onChange={(value) => value && change("sort", value)}
            data={Object.entries(sorts).map(([value, label]) => ({
              value,
              label: t(`sorts.${label}`),
            }))}
          />
        </div>
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <div className="panel-feedback">
            <Failure error={result.error} retry={() => result.refetch()} />
          </div>
        ) : result.data.items.length === 0 ? (
          <div className="feedback">
            <BookOpen size={36} />
            <h2>{t("catalog.empty")} </h2>
            <p>
              {filterKeys.some((key) => params.has(key))
                ? t("catalog.emptyFiltered")
                : t("catalog.emptyCatalog")}
            </p>
            {!filterKeys.some((key) => params.has(key)) &&
              Number(params.get("page")) === 0 && (
                <p>
                  <Link className="back-link" to="/settings/database">
                    {t("import.firstLoad")}
                  </Link>
                </p>
              )}
            <Button variant="light" onClick={() => setSearch({})}>
              {t("catalog.reset")}{" "}
            </Button>
          </div>
        ) : (
          <div className="table-scroll">
            <table>
              <caption className="sr-only">{t("catalog.caption")} </caption>
              <thead>
                <tr>
                  <th scope="col">{t("catalog.book")} </th>
                  <th scope="col">{t("catalog.author")} </th>
                  <th scope="col">{t("catalog.language")} </th>
                  <th scope="col">{t("catalog.year")} </th>
                  <th scope="col">{t("catalog.revision")} </th>
                  <th scope="col">
                    <span className="sr-only">{t("catalog.detail")} </span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.data.items.map((book) => (
                  <tr key={book.eplId}>
                    <td>
                      <Link
                        className="book-title"
                        onClick={rememberScroll}
                        to={`/catalog/${book.eplId}`}
                        state={{ catalogSearch: search.toString() }}
                      >
                        <BookCover book={book} />
                        <span>
                          {book.title}
                          <small>EPL {book.eplId}</small>
                        </span>
                      </Link>
                    </td>
                    <td>{book.author}</td>
                    <td>
                      <span className="badge">{language(book.language)}</span>
                    </td>
                    <td>
                      {number(book.publicationYear, { useGrouping: false })}
                    </td>
                    <td>{number(book.revision)}</td>
                    <td>
                      <Link
                        className="row-link"
                        target="_blank"
                        rel="noopener noreferrer"
                        title={t("catalog.openNew", { title: book.title })}
                        aria-label={t("catalog.openNew", { title: book.title })}
                        to={`/catalog/${book.eplId}`}
                        state={{ catalogSearch: search.toString() }}
                      >
                        <ArrowUpRight size={18} />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="pagination">
          <Select
            className="page-size-select"
            withCheckIcon={false}
            aria-label={t("catalog.size")}
            value={params.get("size")}
            onChange={(value) => value && change("size", value)}
            data={[10, 20, 50, 100, 200, 500, 1000].map((n) => ({
              value: String(n),
              label: t("catalog.perPage", { count: n }),
            }))}
          />
          <PageJump
            page={Number(params.get("page"))}
            totalPages={result.data?.meta.totalPages ?? 0}
            disabled={!result.data || result.isFetching}
            onPage={(page) => change("page", String(page))}
          />
          <Button
            variant="default"
            disabled={!result.data?.meta.hasPrevious || result.isFetching}
            onClick={() =>
              change("page", String(Number(params.get("page")) - 1))
            }
          >
            {t("catalog.previous")}{" "}
          </Button>
          <Button
            variant="default"
            disabled={!result.data?.meta.hasNext || result.isFetching}
            onClick={() =>
              change("page", String(Number(params.get("page")) + 1))
            }
          >
            {t("catalog.next")}{" "}
          </Button>
        </div>
      </section>
    </>
  );
}
