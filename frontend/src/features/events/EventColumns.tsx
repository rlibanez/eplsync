import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";

const columns = [
  "startedAt",
  "finishedAt",
  "duration",
  "category",
  "event",
  "outcome",
  "origin",
  "user",
  "summary",
] as const;
const defaults = [195, 195, 110, 140, 195, 170, 120, 170, 480];
const storageKey = "eplsync.events.columnWidths";
const clamp = (width: number) => Math.min(1200, Math.max(90, width));

export function useEventColumns() {
  const { t } = useTranslation();
  const [widths, setWidths] = useState<number[]>(() => {
    try {
      const saved: unknown = JSON.parse(
        localStorage.getItem(storageKey) || "null",
      );
      if (
        Array.isArray(saved) &&
        saved.length === columns.length - 1 &&
        saved.every(
          (value) => typeof value === "number" && Number.isFinite(value),
        )
      )
        return [...saved.slice(0, 7), 170, ...saved.slice(7)].map(clamp);
      if (
        Array.isArray(saved) &&
        saved.length === columns.length &&
        saved.every(
          (value) => typeof value === "number" && Number.isFinite(value),
        )
      )
        return saved.map(clamp);
    } catch {
      /* Storage can be unavailable. */
    }
    return defaults;
  });
  const drag = useRef<{
    index: number;
    x: number;
    width: number;
    widths: number[];
  } | null>(null);
  const measure = (button: HTMLButtonElement) =>
    Array.from(
      button.closest("tr")!.children,
      (cell) => cell.getBoundingClientRect().width,
    );
  const update = (index: number, width: number, measured?: number[]) =>
    setWidths((current) => {
      const next = (measured ?? current).map((value, i) =>
        i === index ? clamp(width) : value,
      );
      try {
        localStorage.setItem(storageKey, JSON.stringify(next));
      } catch {
        /* Optional persistence. */
      }
      return next;
    });
  return {
    width: widths.reduce((sum, width) => sum + width, 0),
    colgroup: (
      <colgroup>
        {columns.map((key, index) => (
          <col key={key} style={{ width: widths[index] }} />
        ))}
      </colgroup>
    ),
    headers: columns.map((key, index) => (
      <th key={key}>
        {t("events." + key)}
        <button
          type="button"
          className="column-resizer"
          aria-label={t("columns.resize", { column: t("events." + key) })}
          onPointerDown={(event) => {
            const measured = measure(event.currentTarget);
            drag.current = {
              index,
              x: event.clientX,
              width: measured[index],
              widths: measured,
            };
            event.currentTarget.setPointerCapture(event.pointerId);
            event.preventDefault();
          }}
          onPointerMove={(event) => {
            if (drag.current?.index === index)
              update(
                index,
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
            if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
              event.preventDefault();
              const measured = measure(event.currentTarget);
              update(
                index,
                measured[index] + (event.key === "ArrowRight" ? 20 : -20),
                measured,
              );
            }
          }}
        />
      </th>
    )),
  };
}
