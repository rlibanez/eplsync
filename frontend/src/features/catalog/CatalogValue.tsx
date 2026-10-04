import { Fragment } from "react";
import { Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { Book } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { columnLabels, type Column } from "./CatalogColumns";
import { localDay } from "./CatalogFilters";

export function quickFilter(
  params: URLSearchParams,
  column: Column,
  value: string,
) {
  const next = new URLSearchParams(params);
  if (column === "publicationYear" || column === "publicationDate" || column === "pages") {
    next.delete(column);
    next.set(column + "From", value);
    next.set(column + "To", value);
  } else if (column === "insertDate") {
    const day = localDay(new Date(value));
    next.set("addedFrom", day);
    next.set("addedTo", day);
  } else {
    if (!next.getAll(column).includes(value)) next.append(column, value);
  }
  next.set("page", "0");
  return next;
}

export function CatalogValue({
  book,
  column,
  onFilter,
  separator,
}: {
  separator?: string;
  book: Book;
  column: Exclude<Column, "title" | "selection">;
  onFilter: (column: Column, value: string) => void;
}) {
  const { t } = useTranslation();
  const { number, language, date, status } = useLocale();
  const value = book[column];
  if (value === null || value === undefined || String(value).trim() === "")
    return <>—</>;
  const values =
    (column === "genres" || column === "author")
      ? [...new Set(String(value)
          .split(column === "author" ? "&" : ",")
          .map((v) => v.trim())
          .filter(Boolean))]
      : [String(value)];
  return (
    <>
      {values.map((raw, index) => {
        const label =
          column === "language"
            ? language(raw)
            : column === "status" || column === "publicationStatus"
              ? status(raw)
              : column === "publicationDate" || column === "insertDate"
                ? date(raw)
                : column === "eplId" || column === "publicationYear"
                  ? number(Number(raw), { useGrouping: false })
                  : column === "revision"
                    ? String(Number(raw))
                    : raw;
        const help = t("catalog.filterByValue", {
          field: t(columnLabels[column]),
          value: label,
        });
        return (
          <Fragment key={raw}>
            {index > 0 && (separator ?? (column === "author" ? " & " : ", "))}
            <Tooltip label={help} multiline w={280}>
              <button
                type="button"
                className="catalog-value-filter"
                aria-label={help}
                onClick={() => onFilter(column, raw)}
              >
                {column === "language" ? (
                  <span className="badge">{label}</span>
                ) : (
                  label
                )}
              </button>
            </Tooltip>
          </Fragment>
        );
      })}
    </>
  );
}
