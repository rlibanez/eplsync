import { CatalogCards } from "./CatalogCards";
import { ImportWizard } from "../maintenance/ImportWizard";
import { useImport } from "../maintenance/ImportProvider";
import {
  useMagnetExport,
  selectionQuery,
  useCatalogSelection,
} from "./CatalogSelection";
import { CatalogSorting } from "./CatalogSorting";
import { SendSelection } from "../downloads/SendSelection";
import { CatalogValue, quickFilter } from "./CatalogValue";
import {
  CatalogColumns,
  columnLabels,
  useCatalogColumns,
  type Column,
} from "./CatalogColumns";
import { CatalogFilters, filterKeys, filterRequest } from "./CatalogFilters";
import { BookCover } from "./BookCover";
import { PageJump } from "../../components/PageJump";
import { useEffect, useRef, useState } from "react";
import { rememberCatalog } from "./navigation";
import { useLocale } from "../../locales/useLocale";
import { useTranslation } from "react-i18next";
import { useCatalogScroll } from "./useCatalogScroll";
import { useQuery } from "@tanstack/react-query";
import { Button, Select, Checkbox, Menu } from "@mantine/core";
import { Link, useSearchParams, useNavigate } from "react-router-dom";
import {
  BookOpen,
  Download,
  Send,
  ArrowUp,
  ArrowDown,
  Table2,
  LayoutGrid,
  PanelsTopLeft,
  ChevronDown,
  Check,
} from "lucide-react";
import { get, catalogParams, type BookPage } from "../../api/catalog";
import { Loading, Failure } from "../../components/Feedback";
export function Catalog() {
  const { t } = useTranslation();
  const { number } = useLocale();
  const columns = useCatalogColumns();
  const navigate = useNavigate();
  const importer = useImport();
  const [wizard, setWizard] = useState(false);
  const [view, setView] = useState<"table" | "grid" | "mosaic">(() => {
    try {
      const stored = localStorage.getItem("eplsync.catalog.view");
      return stored === "grid" || stored === "mosaic" ? stored : "table";
    } catch {
      return "table";
    }
  });
  const changeView = (value: string) => {
    if (value !== "table" && value !== "grid" && value !== "mosaic") return;
    setView(value);
    try {
      localStorage.setItem("eplsync.catalog.view", value);
    } catch {
      /* Storage may be unavailable. */
    }
  };
  const magnetExport = useMagnetExport();
  const filtersOpen = useRef(false);
  const resize = useRef<{
    key: Column;
    x: number;
    width: number;
    widths: Partial<Record<Column, number>>;
  } | null>(null);
  const columnWidth = (key: Column) =>
    columns.settings.widths[key] ??
    (key === "selection" ? 70 : key === "title" ? 320 : 180);
  const resized = Object.keys(columns.settings.widths).length > 0;
  const [search, setSearch] = useSearchParams();
  useEffect(() => {
    rememberCatalog(search.toString());
  }, [search]);
  const params = catalogParams(search, false);
  const ordering = params.getAll("sort");
  const query = params.toString();
  const result = useQuery({
    queryKey: ["catalog", query],
    queryFn: ({ signal }) =>
      get<BookPage>(
        `/catalog/books?${filterRequest(catalogParams(params))}`,
        signal,
      ),
  });
  const selection = useCatalogSelection(selectionQuery(params));
  const count = selection.count(result.data?.meta.totalItems ?? 0);
  const pageIds = result.data?.items.map((book) => book.eplId) ?? [];
  const selectedOnPage = pageIds.filter(selection.isSelected).length;
  const [action, setAction] = useState<{
    type: "send";
    filters: Record<string, unknown>;
    count: number;
    all: boolean;
  } | null>(null);
  const rememberScroll = useCatalogScroll(query, result.isSuccess);
  function change(key: string, value: string) {
    const next = new URLSearchParams(params);
    next.set(key, value);
    if (key !== "page") next.set("page", "0");
    setSearch(next);
  }
  function changeSort(values: string[]) {
    const next = new URLSearchParams(params);
    next.delete("sort");
    values.forEach((value) => next.append("sort", value));
    next.set("page", "0");
    setSearch(next);
  }
  function sortColumn(key: string, additive: boolean) {
    const index = ordering.findIndex((value) => value.startsWith(key + ","));
    const value =
      key + (index >= 0 && ordering[index].endsWith(",asc") ? ",desc" : ",asc");
    if (!additive) changeSort([value]);
    else if (index < 0) changeSort([...ordering, value]);
    else changeSort(ordering.map((old, i) => (i === index ? value : old)));
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>{t("nav.catalog")} </h1>
        </div>
        <Button
          leftSection={<Download size={16} />}
          onClick={() => setWizard(true)}
          disabled={importer.restoring || !!importer.operation?.pending}
        >
          {t("import.wizardTitle")}
        </Button>
      </div>
      <CatalogFilters
        initiallyOpen={filtersOpen.current}
        onToggle={(open) => {
          filtersOpen.current = open;
        }}
        key={search.toString()}
        params={params}
        onChange={setSearch}
      />
      <section className="panel">
        <div className="table-toolbar">
          <div className="catalog-toolbar-selection">
            <Checkbox
              label={t("selection.selectPage")}
              checked={pageIds.length > 0 && selectedOnPage === pageIds.length}
              indeterminate={
                selectedOnPage > 0 && selectedOnPage < pageIds.length
              }
              disabled={!pageIds.length || result.isFetching}
              onChange={(e) =>
                selection.toggle(pageIds, e.currentTarget.checked)
              }
            />
            <Button
              variant="subtle"
              size="compact-sm"
              fw={400}
              disabled={!result.data?.meta.totalItems || result.isFetching}
              onClick={selection.selectAll}
            >
              {t("selection.selectAll", {
                count: result.data?.meta.totalItems ?? 0,
                formattedCount: number(result.data?.meta.totalItems ?? 0),
              })}
            </Button>
          </div>
          <div className="catalog-table-controls">
            <Menu position="bottom-end" shadow="md">
              <Menu.Target>
                <Button
                  variant="default"
                  fw={400}
                  leftSection={
                    view === "table" ? (
                      <Table2 size={16} />
                    ) : view === "grid" ? (
                      <LayoutGrid size={16} />
                    ) : (
                      <PanelsTopLeft size={16} />
                    )
                  }
                  rightSection={<ChevronDown size={14} />}
                  aria-label={t("catalog.viewSelector")}
                >
                  {t(
                    view === "table"
                      ? "catalog.viewTable"
                      : view === "grid"
                        ? "catalog.viewGrid"
                        : "catalog.viewMosaic",
                  )}
                </Button>
              </Menu.Target>
              <Menu.Dropdown>
                {(
                  [
                    {
                      value: "table",
                      label: "catalog.viewTable",
                      icon: Table2,
                    },
                    {
                      value: "grid",
                      label: "catalog.viewGrid",
                      icon: LayoutGrid,
                    },
                    {
                      value: "mosaic",
                      label: "catalog.viewMosaic",
                      icon: PanelsTopLeft,
                    },
                  ] as const
                ).map((option) => (
                  <Menu.Item
                    key={option.value}
                    leftSection={<option.icon size={16} />}
                    rightSection={
                      view === option.value ? (
                        <Check size={16} aria-hidden="true" />
                      ) : null
                    }
                    aria-current={view === option.value ? "true" : undefined}
                    onClick={() => changeView(option.value)}
                  >
                    {t(option.label)}
                  </Menu.Item>
                ))}
              </Menu.Dropdown>
            </Menu>
            <CatalogSorting ordering={ordering} onChange={changeSort} />
            {view === "table" && (
              <CatalogColumns
                settings={columns.settings}
                update={columns.update}
              />
            )}
          </div>
        </div>
        {count > 0 && (
          <div className="catalog-selection-bar has-selection">
            <div className="catalog-selection-info">
              {count > 0 && (
                <span className="catalog-selection-count" aria-live="polite">
                  {t(
                    selection.all
                      ? "selection.allSelected"
                      : "selection.selected",
                    { count },
                  )}
                </span>
              )}
              <div className="catalog-selection-controls">
                {count > 0 && (
                  <Button
                    variant="subtle"
                    size="compact-sm"
                    onClick={selection.clear}
                  >
                    {t("selection.clear")}
                  </Button>
                )}
              </div>
            </div>
            {count > 0 && (
              <div className="catalog-selection-actions">
                <Button
                  variant="light"
                  leftSection={<Download size={16} />}
                  loading={magnetExport.busy}
                  onClick={() => void magnetExport.save(selection.filters)}
                >
                  {t("selection.export")}
                </Button>
                <Button
                  leftSection={<Send size={16} />}
                  onClick={() =>
                    setAction({
                      type: "send",
                      filters: selection.filters,
                      count,
                      all: selection.all,
                    })
                  }
                >
                  {t("selection.send")}
                </Button>
              </div>
            )}
          </div>
        )}
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
            {(filterKeys.some((key) => params.has(key)) ||
              Number(params.get("page")) > 0) && (
              <Button variant="light" onClick={() => setSearch({})}>
                {t("catalog.reset")}
              </Button>
            )}
          </div>
        ) : view !== "table" ? (
          <CatalogCards
            books={result.data.items}
            view={view}
            search={search.toString()}
            isSelected={selection.isSelected}
            toggle={selection.toggle}
            rememberScroll={rememberScroll}
            onFilter={(column, value) =>
              setSearch((current) =>
                quickFilter(catalogParams(current, false), column, value),
              )
            }
          />
        ) : (
          <div className="table-scroll">
            <table
              className={`catalog-table${resized ? " resized" : ""}`}
              style={
                resized
                  ? {
                      width: columns.visible.reduce(
                        (sum, key) => sum + columnWidth(key),
                        0,
                      ),
                    }
                  : undefined
              }
            >
              {resized && (
                <colgroup>
                  {columns.visible.map((key) => (
                    <col key={key} style={{ width: columnWidth(key) }} />
                  ))}
                </colgroup>
              )}
              <caption className="sr-only">{t("catalog.caption")} </caption>
              <thead>
                <tr>
                  {columns.visible.map((key) => (
                    <th
                      scope="col"
                      key={key}
                      data-column={key}
                      aria-sort={
                        ordering.includes(`${key},asc`)
                          ? "ascending"
                          : ordering.includes(`${key},desc`)
                            ? "descending"
                            : undefined
                      }
                    >
                      {key === "selection" ? (
                        <Checkbox
                          aria-label={t("selection.page")}
                          checked={
                            pageIds.length > 0 &&
                            selectedOnPage === pageIds.length
                          }
                          indeterminate={
                            selectedOnPage > 0 &&
                            selectedOnPage < pageIds.length
                          }
                          onChange={(e) =>
                            selection.toggle(pageIds, e.currentTarget.checked)
                          }
                        />
                      ) : (
                        <button
                          type="button"
                          className="catalog-sort-heading"
                          onClick={(event) => sortColumn(key, event.shiftKey)}
                        >
                          {t(columnLabels[key])}
                          {ordering.includes(`${key},asc`) && (
                            <ArrowUp size={14} aria-hidden="true" />
                          )}
                          {ordering.includes(`${key},desc`) && (
                            <ArrowDown size={14} aria-hidden="true" />
                          )}
                          {ordering.length > 1 &&
                            ordering.some((value) =>
                              value.startsWith(key + ","),
                            ) && (
                              <span className="catalog-sort-priority">
                                {ordering.findIndex((value) =>
                                  value.startsWith(key + ","),
                                ) + 1}
                              </span>
                            )}
                        </button>
                      )}
                      <button
                        className="column-resizer"
                        type="button"
                        aria-label={t("columns.resize", {
                          column: t(columnLabels[key]),
                        })}
                        onPointerDown={(event) => {
                          const header = event.currentTarget.closest("th")!;
                          const widths = { ...columns.settings.widths };
                          header
                            .closest("table")!
                            .querySelectorAll<HTMLElement>("th[data-column]")
                            .forEach((cell) => {
                              widths[cell.dataset.column as Column] =
                                cell.getBoundingClientRect().width;
                            });
                          resize.current = {
                            key,
                            x: event.clientX,
                            width: header.getBoundingClientRect().width,
                            widths,
                          };
                          event.currentTarget.setPointerCapture(
                            event.pointerId,
                          );
                          event.preventDefault();
                        }}
                        onPointerMove={(event) => {
                          if (!resize.current) return;
                          const drag = resize.current;
                          columns.update({
                            ...columns.settings,
                            widths: {
                              ...drag.widths,
                              [drag.key]: Math.max(
                                70,
                                Math.min(
                                  1200,
                                  drag.width + event.clientX - drag.x,
                                ),
                              ),
                            },
                          });
                        }}
                        onPointerUp={() => {
                          resize.current = null;
                        }}
                        onPointerCancel={() => {
                          resize.current = null;
                        }}
                        onLostPointerCapture={() => {
                          resize.current = null;
                        }}
                        onKeyDown={(event) => {
                          if (!["ArrowLeft", "ArrowRight"].includes(event.key))
                            return;
                          event.preventDefault();
                          const width = event.currentTarget
                            .closest("th")!
                            .getBoundingClientRect().width;
                          columns.update({
                            ...columns.settings,
                            widths: {
                              ...columns.settings.widths,
                              [key]: Math.max(
                                70,
                                Math.min(
                                  1200,
                                  width +
                                    (event.key === "ArrowRight" ? 20 : -20),
                                ),
                              ),
                            },
                          });
                        }}
                      />
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.data.items.map((book) => (
                  <tr key={book.eplId}>
                    {columns.visible.map((key) => (
                      <td
                        key={key}
                        className={
                          key === "title" ? "catalog-title-cell" : undefined
                        }
                      >
                        {key === "selection" ? (
                          <Checkbox
                            aria-label={t("selection.book", {
                              title: book.title,
                            })}
                            checked={selection.isSelected(book.eplId)}
                            onChange={(e) =>
                              selection.toggle(
                                [book.eplId],
                                e.currentTarget.checked,
                              )
                            }
                          />
                        ) : key === "title" ? (
                          <Link
                            className="book-title"
                            onClick={rememberScroll}
                            to={`/catalog/${book.eplId}`}
                            state={{ catalogSearch: search.toString() }}
                          >
                            <BookCover book={book} />
                            <span>{book.title}</span>
                          </Link>
                        ) : (
                          <CatalogValue
                            book={book}
                            column={key}
                            onFilter={(column, value) =>
                              setSearch((current) =>
                                quickFilter(
                                  catalogParams(current, false),
                                  column,
                                  value,
                                ),
                              )
                            }
                          />
                        )}
                      </td>
                    ))}
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
      <ImportWizard
        opened={wizard}
        onClose={() => setWizard(false)}
        onStart={(mode, source) => {
          void importer.run(mode, source);
          navigate("/settings/database");
        }}
      />
      {action?.type === "send" && (
        <SendSelection
          filters={action.filters}
          count={action.count}
          allResults={action.all}
          onClose={() => setAction(null)}
        />
      )}
    </>
  );
}
