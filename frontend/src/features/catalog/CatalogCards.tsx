import { Checkbox, Tooltip } from "@mantine/core";
import { useState } from "react";
import { Link, type LinkProps } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { Book } from "../../api/catalog";
import { BookCover } from "./BookCover";
import { CatalogValue } from "./CatalogValue";
import type { Column } from "./CatalogColumns";
import { useLocale } from "../../locales/useLocale";

function CardTitle({ title, ...props }: LinkProps & { title: string }) {
  const [clipped, setClipped] = useState(false);
  const measure = (element: HTMLAnchorElement) =>
    setClipped(
      element.scrollHeight > element.clientHeight ||
        element.scrollWidth > element.clientWidth,
    );
  return (
    <Tooltip
      label={title}
      disabled={!clipped}
      multiline
      maw={360}
      events={{ hover: true, focus: true, touch: false }}
    >
      <Link
        {...props}
        className="catalog-card-title"
        onMouseEnter={(event) => measure(event.currentTarget)}
        onFocus={(event) => measure(event.currentTarget)}
      >
        {title}
      </Link>
    </Tooltip>
  );
}

export function CatalogCards({
  books,
  view,
  search,
  isSelected,
  toggle,
  rememberScroll,
  onFilter,
}: {
  books: Book[];
  view: "grid" | "mosaic";
  search: string;
  isSelected: (id: number) => boolean;
  toggle: (ids: number[], selected: boolean) => void;
  rememberScroll: () => void;
  onFilter: (column: Column, value: string) => void;
}) {
  const { t } = useTranslation();
  const { number, status } = useLocale();
  return (
    <div className={`catalog-cards catalog-cards-${view}`}>
      {books.map((book) => (
        <article
          key={book.eplId}
          className={`catalog-card${isSelected(book.eplId) ? " is-selected" : ""}`}
        >
          <Checkbox
            className="catalog-card-selection"
            aria-label={t("selection.book", { title: book.title })}
            checked={isSelected(book.eplId)}
            onChange={(e) => toggle([book.eplId], e.currentTarget.checked)}
          />
          <Link
            className="catalog-card-cover"
            to={`/catalog/${book.eplId}`}
            state={{ catalogSearch: search }}
            onClick={rememberScroll}
            aria-label={book.title}
          >
            <BookCover book={book} />
          </Link>
          <div className="catalog-card-info">
            <CardTitle
              title={book.title}
              to={`/catalog/${book.eplId}`}
              state={{ catalogSearch: search }}
              onClick={rememberScroll}
            />
            <div className="catalog-card-author">
              <CatalogValue book={book} column="author" onFilter={onFilter} />
            </div>
            {view === "mosaic" && (
              <>
                {book.collection && (
                  <div className="catalog-card-collection">
                    <CatalogValue
                      book={book}
                      column="collection"
                      onFilter={onFilter}
                    />
                    {book.volume != null && (
                      <span> · {number(book.volume)}</span>
                    )}
                  </div>
                )}
                <div className="catalog-card-facts">
                  {book.language && (
                    <CatalogValue
                      book={book}
                      column="language"
                      onFilter={onFilter}
                    />
                  )}
                  {book.publicationYear != null && (
                    <CatalogValue
                      book={book}
                      column="publicationYear"
                      onFilter={onFilter}
                    />
                  )}
                  {book.pages != null && (
                    <span>
                      {t("catalog.cardPages", {
                        count: book.pages,
                        formattedCount: number(book.pages),
                      })}
                    </span>
                  )}
                </div>
                {book.genres && (
                  <div className="catalog-card-genres">
                    <CatalogValue
                      book={book}
                      column="genres"
                      onFilter={onFilter}
                    />
                  </div>
                )}
                {!!book.download?.items.length && (
                  <div className="catalog-card-downloads">
                    {[
                      ...new Set(
                        book.download.statuses ??
                          book.download.items.map((item) => item.status),
                      ),
                    ].map((value) => (
                      <span className="badge" key={value}>
                        {status(value)}
                      </span>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
        </article>
      ))}
    </div>
  );
}
