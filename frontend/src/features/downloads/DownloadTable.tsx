import { useRef, useState } from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { BookCover } from "../catalog/BookCover";
export type TableColumn = { field: string; label: string; width: number };
export function useDownloadColumns(
  key: string,
  columns: TableColumn[],
  sort: string,
  onSort: (value: string) => void,
) {
  const { t } = useTranslation();
  const [widths, setWidths] = useState<number[]>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(key) || "null");
      if (
        Array.isArray(saved) &&
        saved.length === columns.length &&
        saved.every((v) => typeof v === "number" && Number.isFinite(v))
      )
        return saved.map((v) => Math.min(1200, Math.max(80, v)));
    } catch {
      /* Optional storage. */
    }
    return columns.map((c) => c.width);
  });
  const drag = useRef<{ index: number; x: number; widths: number[] } | null>(
    null,
  );
  function update(index: number, width: number, base: number[]) {
    const next = base.map((v, i) =>
      i === index ? Math.min(1200, Math.max(80, width)) : v,
    );
    setWidths(next);
    try {
      localStorage.setItem(key, JSON.stringify(next));
    } catch {
      /* Optional storage. */
    }
  }
  return {
    width: widths.reduce((a, b) => a + b, 0),
    colgroup: (
      <colgroup>
        {columns.map((c, i) => (
          <col key={c.field} style={{ width: widths[i] }} />
        ))}
      </colgroup>
    ),
    headings: (
      <tr>
        {columns.map((c, i) => (
          <th
            key={c.field}
            scope="col"
            aria-sort={
              sort.startsWith(c.field + ",")
                ? sort.endsWith(",asc")
                  ? "ascending"
                  : "descending"
                : undefined
            }
          >
            <button
              className="catalog-sort-heading"
              onClick={() =>
                onSort(
                  `${c.field},${sort === c.field + ",asc" ? "desc" : "asc"}`,
                )
              }
            >
              {t(c.label)}
              {sort.startsWith(c.field + ",") &&
                (sort.endsWith(",asc") ? (
                  <ArrowUp size={14} />
                ) : (
                  <ArrowDown size={14} />
                ))}
            </button>
            <button
              className="column-resizer"
              aria-label={t("columns.resize", { column: t(c.label) })}
              onPointerDown={(e) => {
                drag.current = { index: i, x: e.clientX, widths: [...widths] };
                e.currentTarget.setPointerCapture(e.pointerId);
                e.preventDefault();
              }}
              onPointerMove={(e) => {
                if (drag.current?.index === i)
                  update(
                    i,
                    drag.current.widths[i] + e.clientX - drag.current.x,
                    drag.current.widths,
                  );
              }}
              onPointerUp={() => {
                drag.current = null;
              }}
              onPointerCancel={() => {
                drag.current = null;
              }}
              onLostPointerCapture={() => {
                drag.current = null;
              }}
              onKeyDown={(e) => {
                if (["ArrowLeft", "ArrowRight"].includes(e.key)) {
                  e.preventDefault();
                  update(
                    i,
                    widths[i] + (e.key === "ArrowRight" ? 20 : -20),
                    widths,
                  );
                }
              }}
            />
          </th>
        ))}
      </tr>
    ),
  };
}
export function DownloadBook({
  book,
}: {
  book: {
    eplId: number;
    title?: string | null;
    coverUrl?: string | null;
    coverAvailable?: boolean | null;
  };
}) {
  const title = book.title || `EPL ${book.eplId}`;
  return (
    <Link className="book-title" to={`/catalog/${book.eplId}`}>
      <BookCover
        book={{
          eplId: book.eplId,
          title,
          coverUrl: book.coverUrl ?? null,
          coverAvailable: book.coverAvailable ?? false,
        }}
      />
      <span>{title}</span>
    </Link>
  );
}
