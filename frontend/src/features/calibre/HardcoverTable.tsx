import { useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

/** Content-sized until the user resizes a column; widths belong to this wizard. */
export function HardcoverTable({
  label,
  headings,
  children,
  busy = false,
}: {
  label: string;
  headings: string[];
  children: ReactNode;
  busy?: boolean;
}) {
  const { t } = useTranslation();
  const table = useRef<HTMLTableElement>(null);
  const drag = useRef<{ index: number; x: number; widths: number[] } | null>(
    null,
  );
  const [widths, setWidths] = useState<number[] | null>(null);
  const measure = () =>
    Array.from(
      table.current!.tHead!.rows[0].cells,
      (cell) => cell.getBoundingClientRect().width,
    );
  const resize = (index: number, width: number, base: number[]) =>
    setWidths(
      base.map((value, at) =>
        at === index ? Math.max(90, Math.min(1400, width)) : value,
      ),
    );
  return (
    <div className="table-scroll">
      <table
        ref={table}
        aria-label={label}
        aria-busy={busy}
        className={`hardcover-table${widths ? " hardcover-table-resized" : ""}`}
        style={
          widths
            ? {
                width: widths.reduce((sum, width) => sum + width, 0),
                tableLayout: "fixed",
              }
            : undefined
        }
      >
        {widths && (
          <colgroup>
            {widths.map((width, index) => (
              <col key={index} style={{ width }} />
            ))}
          </colgroup>
        )}
        <thead>
          <tr>
            {headings.map((heading, index) => (
              <th key={index}>
                {heading}
                <button
                  type="button"
                  className="column-resizer"
                  aria-label={t("columns.resize", { column: heading })}
                  onPointerDown={(event) => {
                    drag.current = {
                      index,
                      x: event.clientX,
                      widths: measure(),
                    };
                    event.currentTarget.setPointerCapture(event.pointerId);
                    event.preventDefault();
                  }}
                  onPointerMove={(event) => {
                    if (drag.current?.index === index)
                      resize(
                        index,
                        drag.current.widths[index] +
                          event.clientX -
                          drag.current.x,
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
                    if (
                      event.key === "ArrowLeft" ||
                      event.key === "ArrowRight"
                    ) {
                      event.preventDefault();
                      const base = widths ?? measure();
                      resize(
                        index,
                        base[index] + (event.key === "ArrowRight" ? 20 : -20),
                        base,
                      );
                    }
                  }}
                  onDoubleClick={() => setWidths(null)}
                />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
