import { CatalogColumns, columnLabels, useCatalogColumns, type Column } from "./CatalogColumns";
import { CatalogFilters, filterKeys, filterRequest } from "./CatalogFilters";
import { BookCover } from "./BookCover";
import { PageJump } from "../../components/PageJump";
import { useEffect, useRef } from "react";
import { rememberCatalog } from "./navigation";
import { useLocale } from "../../locales/useLocale";
import { useTranslation } from "react-i18next";
import { useCatalogScroll } from "./useCatalogScroll";
import { useQuery } from "@tanstack/react-query";
import { Button, Select } from "@mantine/core";
import { Link, useSearchParams } from "react-router-dom";
import { BookOpen } from "lucide-react";
import { get, catalogParams, sorts, type BookPage } from "../../api/catalog";
import { Loading, Failure } from "../../components/Feedback";
export function Catalog() {
  const { t } = useTranslation();
  const { number, language, date, status } = useLocale();
  const columns = useCatalogColumns();
  const resize = useRef<{ key: Column; x: number; width: number; widths: Partial<Record<Column, number>> } | null>(null);
  const columnWidth = (key: Column) => columns.settings.widths[key] ?? (key === "title" ? 320 : 180);
  const resized = Object.keys(columns.settings.widths).length > 0;
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
          <div className="catalog-table-controls">
          <Select
            aria-label={t("catalog.sort")}
            value={params.get("sort")}
            onChange={(value) => value && change("sort", value)}
            data={Object.entries(sorts).map(([value, label]) => ({
              value,
              label: t(`sorts.${label}`),
            }))}
          />
          <CatalogColumns settings={columns.settings} update={columns.update} />
          </div>
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
            <table className={`catalog-table${resized ? " resized" : ""}`} style={resized ? { width: columns.visible.reduce((sum, key) => sum + columnWidth(key), 0) } : undefined}>
              {resized && <colgroup>{columns.visible.map(key => <col key={key} style={{ width: columnWidth(key) }} />)}</colgroup>}
              <caption className="sr-only">{t("catalog.caption")} </caption>
              <thead>
                <tr>
                  {columns.visible.map(key => <th scope="col" key={key} data-column={key}>{t(columnLabels[key])}
                    <button className="column-resizer" type="button" aria-label={t("columns.resize", { column: t(columnLabels[key]) })}
                      onPointerDown={event => {
                        const header = event.currentTarget.closest("th")!;
                        const widths = { ...columns.settings.widths };
                        header.closest("table")!.querySelectorAll<HTMLElement>("th[data-column]").forEach(cell => { widths[cell.dataset.column as Column] = cell.getBoundingClientRect().width; });
                        resize.current = { key, x: event.clientX, width: header.getBoundingClientRect().width, widths };
                        event.currentTarget.setPointerCapture(event.pointerId);
                        event.preventDefault();
                      }}
                      onPointerMove={event => {
                        if (!resize.current) return;
                        const drag = resize.current;
                        columns.update({ ...columns.settings, widths: { ...drag.widths, [drag.key]: Math.max(70, Math.min(1200, drag.width + event.clientX - drag.x)) } });
                      }}
                      onPointerUp={() => { resize.current = null; }}
                      onPointerCancel={() => { resize.current = null; }}
                      onLostPointerCapture={() => { resize.current = null; }}
                      onKeyDown={event => {
                        if (!["ArrowLeft", "ArrowRight"].includes(event.key)) return;
                        event.preventDefault();
                        const width = event.currentTarget.closest("th")!.getBoundingClientRect().width;
                        columns.update({ ...columns.settings, widths: { ...columns.settings.widths, [key]: Math.max(70, Math.min(1200, width + (event.key === "ArrowRight" ? 20 : -20))) } });
                      }} />
                  </th>)}

                </tr>
              </thead>
              <tbody>
                {result.data.items.map((book) => (
                  <tr key={book.eplId}>
                    {columns.visible.map(key => <td key={key}>
                      {key === "title" ? <Link className="book-title" onClick={rememberScroll}
                        to={`/catalog/${book.eplId}`} state={{ catalogSearch: search.toString() }}>
                        <BookCover book={book} /><span>{book.title}</span>
                      </Link> : key === "language" ? <span className="badge">{language(book.language)}</span>
                        : key === "status" || key === "publicationStatus" ? status(book[key])
                        : key === "publicationDate" || key === "insertDate" ? date(book[key])
                        : key === "eplId" || key === "publicationYear" ? number(book[key], { useGrouping: false })
                        : key === "revision" ? number(book.revision)
                        : book[key] || "—"}
                    </td>)}

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
