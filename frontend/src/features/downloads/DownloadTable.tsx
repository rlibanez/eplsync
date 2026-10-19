import { BookLink } from "../catalog/BookLink";
import { ROW_NUMBER, RowNumberCell, rowNumberWidth } from "../../components/RowNumber";
import {
  cloneElement,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
import { useTranslation } from "react-i18next";
import { BookCover } from "../catalog/BookCover";
import {
  TableColumns,
  TableSorting,
  ordering,
  type ColumnSettings,
} from "./TableControls";
export type TableColumn = {
  field: string;
  label: string;
  width: number;
  sortable?: boolean;
};
const clamp = (value: number) => Math.min(1200, Math.max(80, value));
export function useDownloadColumns(
  key: string,
  inputColumns: TableColumn[],
  sort: string,
  onSort: (value: string) => void,
  options: {
    defaults: string;
    selection?: boolean;
    hidden?: string[];
    extraSort?: TableColumn[];
    newColumnsAfter?: Record<string, string>;
  } = { defaults: "createdAt,desc" },
  lastRowNumber = 1,
) {
  const { t } = useTranslation();
  const columns: TableColumn[] = [
    {
      field: ROW_NUMBER,
      label: "columns.rowNumber",
      width: rowNumberWidth(lastRowNumber),
      sortable: false,
    },
    ...inputColumns,
  ];
  const fields = columns.map((column) => column.field);
  const [settings, setSettings] = useState<ColumnSettings>(() => {
    const initial = { order: fields, hidden: options.hidden ?? [], widths: {} };
    try {
      const saved = JSON.parse(
        localStorage.getItem(`${key}.settings.v1`) || "null",
      );
      if (saved && Array.isArray(saved.order) && Array.isArray(saved.hidden)) {
        const order = Array.from(
          new Set<string>(
            saved.order.filter(
              (field: unknown) =>
                typeof field === "string" && fields.includes(field),
            ),
          ),
        );
        fields.forEach((field) => {
          if (!order.includes(field)) {
            if (field === ROW_NUMBER) order.unshift(field);
            else {
              const previous = options.newColumnsAfter?.[field];
              const index = previous ? order.indexOf(previous) : -1;
              if (index >= 0) order.splice(index + 1, 0, field);
              else order.push(field);
            }
          }
        });
        const hidden = fields.filter(
          (field) =>
            saved.hidden.includes(field) ||
            (!saved.order.includes(field) && options.hidden?.includes(field)),
        );
        const widths: Record<string, number> = {};
        fields.forEach((field) => {
          const value = saved.widths?.[field];
          if (typeof value === "number" && Number.isFinite(value))
            widths[field] = clamp(value);
        });
        return {
          order,
          hidden: hidden.length === fields.length ? [] : hidden,
          widths,
        };
      }
      const legacy = JSON.parse(localStorage.getItem(key) || "null");
      if (
        Array.isArray(legacy) &&
        (legacy.length === columns.length || legacy.length === inputColumns.length) &&
        legacy.every(
          (value) => typeof value === "number" && Number.isFinite(value),
        )
      )
        return {
          ...initial,
          widths: Object.fromEntries(
            (legacy.length === inputColumns.length ? inputColumns.map(column => column.field) : fields).map((field, index) => [field, clamp(legacy[index])]),
          ),
        };
    } catch {
      /* Optional or obsolete preferences. */
    }
    return initial;
  });
  function update(next: ColumnSettings) {
    setSettings(next);
    try {
      localStorage.setItem(`${key}.settings.v1`, JSON.stringify(next));
    } catch {
      /* Keep working in memory. */
    }
  }
  const visible = settings.order
    .filter((field) => !settings.hidden.includes(field))
    .map((field) => columns.find((column) => column.field === field)!);
  const widthFor = (field: string) =>
    field === ROW_NUMBER ? rowNumberWidth(lastRowNumber) : settings.widths[field] ??
    columns.find((column) => column.field === field)!.width;
  const drag = useRef<{
    field: string;
    x: number;
    width: number;
    widths: Record<string, number>;
  } | null>(null);
  function measure(button: HTMLButtonElement) {
    return Object.fromEntries(
      Array.from(
        button
          .closest("tr")!
          .querySelectorAll<HTMLTableCellElement>("th[data-table-column]"),
        (cell) => [
          cell.dataset.tableColumn!,
          cell.getBoundingClientRect().width,
        ],
      ),
    );
  }
  function resize(
    field: string,
    width: number,
    widths: Record<string, number>,
  ) {
    update({
      ...settings,
      widths: { ...settings.widths, ...widths, [field]: clamp(width) },
    });
  }
  const values = ordering(sort);
  const headings = visible.map((column) => {
    const criterion = values.find((value) =>
      value.startsWith(column.field + ","),
    );
    const priority = values.findIndex((value) =>
      value.startsWith(column.field + ","),
    );
    return (
      <th
        key={column.field}
        data-table-column={column.field}
        data-update-column={column.field}
        scope="col"
        className={column.field === ROW_NUMBER ? "row-number" : undefined}
        style={column.field === ROW_NUMBER ? {minWidth: rowNumberWidth(lastRowNumber)} : undefined}
        title={column.field === ROW_NUMBER ? t("columns.rowNumberHelp") : undefined}
        aria-sort={
          criterion
            ? criterion.endsWith(",asc")
              ? "ascending"
              : "descending"
            : undefined
        }
      >
        {column.sortable === false ? (
          t(column.label)
        ) : (
          <button
            type="button"
            className="catalog-sort-heading"
            onClick={(event) => {
              const next = `${column.field},${criterion === column.field + ",asc" ? "desc" : "asc"}`;
              onSort(
                event.shiftKey
                  ? criterion
                    ? values
                        .map((value) => (value === criterion ? next : value))
                        .join(";")
                    : values.length < 8
                      ? [...values, next].join(";")
                      : sort
                  : next,
              );
            }}
          >
            {t(column.label)}
            {criterion && (
              <>
                {criterion.endsWith(",asc") ? (
                  <ArrowUp size={14} aria-hidden="true" />
                ) : (
                  <ArrowDown size={14} aria-hidden="true" />
                )}
                {values.length > 1 && (
                  <span className="catalog-sort-priority">{priority + 1}</span>
                )}
              </>
            )}
          </button>
        )}
        {column.field !== ROW_NUMBER && (<button
          type="button"
          className="column-resizer"
          aria-label={t("columns.resize", { column: t(column.label) })}
          onPointerDown={(event) => {
            const widths = measure(event.currentTarget);
            drag.current = {
              field: column.field,
              x: event.clientX,
              width: widths[column.field],
              widths,
            };
            event.currentTarget.setPointerCapture(event.pointerId);
            event.preventDefault();
          }}
          onPointerMove={(event) => {
            if (drag.current?.field === column.field)
              resize(
                column.field,
                drag.current.width + event.clientX - drag.current.x,
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
          onKeyDown={(event) => {
            if (["ArrowLeft", "ArrowRight"].includes(event.key)) {
              event.preventDefault();
              const widths = measure(event.currentTarget);
              resize(
                column.field,
                widths[column.field] + (event.key === "ArrowRight" ? 20 : -20),
                widths,
              );
            }
          }}
        />)}
      </th>
    );
  });
  return {
    settings,
    update,
    visible,
    width: visible.reduce(
      (sum, column) => sum + widthFor(column.field),
      options.selection ? 44 : 0,
    ),
    colgroup: (
      <colgroup>
        {options.selection && <col style={{ width: 44 }} />}
        {visible.map((column) => (
          <col key={column.field} style={{ width: widthFor(column.field) }} />
        ))}
      </colgroup>
    ),
    headings: <tr>{headings}</tr>,
    selectionHeadings: (selection: ReactNode) => (
      <tr>
        {selection}
        {headings}
      </tr>
    ),
    cells: (cells: ReactElement[], rowNumber: number) =>
      visible.map((column) =>
        column.field === ROW_NUMBER ? (
          <RowNumberCell key={column.field} value={rowNumber} />
        ) : (
          cloneElement(
            cells[
              inputColumns.findIndex((value) => value.field === column.field)
            ],
            { key: column.field },
          )
        ),
      ),
    controls: (
      <>
        <TableSorting
          columns={[
            ...columns.filter((column) => column.sortable !== false),
            ...(options.extraSort ?? []),
          ]}
          sort={sort}
          onChange={onSort}
          defaults={options.defaults}
        />
        <TableColumns columns={columns} settings={settings} update={update} />
      </>
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
    <BookLink className="book-title" to={`/catalog/${book.eplId}`}>
      <BookCover
        book={{
          eplId: book.eplId,
          title,
          coverUrl: book.coverUrl ?? null,
          coverAvailable: book.coverAvailable ?? null,
        }}
      />
      <span>{title}</span>
    </BookLink>
  );
}
