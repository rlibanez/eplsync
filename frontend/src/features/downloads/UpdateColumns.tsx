import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
const fields = [
  "eplId",
  "title",
  "registeredRevision",
  "availableRevision",
  "status",
] as const;
const defaults = [100, 360, 190, 190, 200];
const storageKey = "eplsync.updates.columnWidths.v3";
const clamp = (width: number) => Math.min(1200, Math.max(80, width));
export function useUpdateColumns(selectable: boolean) {
  const { t } = useTranslation();
  const [widths, setWidths] = useState<number[]>(() => {
    try {
      const current = localStorage.getItem(storageKey);
      const previous = localStorage.getItem("eplsync.updates.columnWidths.v2");
      const saved: unknown = JSON.parse(
        current ||
          previous ||
          localStorage.getItem("eplsync.updates.columnWidths") ||
          "null",
      );
      if (
        Array.isArray(saved) &&
        saved.length === fields.length &&
        saved.every((v) => typeof v === "number" && Number.isFinite(v))
      )
        return (
          !current && previous ? [saved[1], saved[0], ...saved.slice(2)] : saved
        ).map(clamp);
    } catch {
      /* Optional browser storage. */
    }
    return defaults;
  });
  const drag = useRef<{
    field: number;
    x: number;
    width: number;
    widths: number[];
  } | null>(null);
  function measure(button: HTMLButtonElement) {
    return Array.from(
      button.closest("tr")!.querySelectorAll("th[data-update-column]"),
      (cell) => cell.getBoundingClientRect().width,
    );
  }
  function update(index: number, width: number, base: number[]) {
    const next = base.map((v, i) => (i === index ? clamp(width) : v));
    setWidths(next);
    try {
      localStorage.setItem(storageKey, JSON.stringify(next));
    } catch {
      /* Optional persistence. */
    }
  }
  return {
    width: widths.reduce((a, b) => a + b, selectable ? 44 : 0),
    colgroup: (
      <colgroup>
        {selectable && <col style={{ width: 44 }} />}
        {fields.map((field, index) => (
          <col
            key={field}
            style={{ width: field === "title" ? undefined : widths[index] }}
          />
        ))}
      </colgroup>
    ),
    resizer: (field: string) => {
      const index = fields.indexOf(field as (typeof fields)[number]);
      return (
        <button
          type="button"
          className="column-resizer"
          aria-label={t("columns.resize", {
            column: t(`revisionUpdates.${field}`),
          })}
          onPointerDown={(e) => {
            const base = measure(e.currentTarget);
            drag.current = {
              field: index,
              x: e.clientX,
              width: base[index],
              widths: base,
            };
            e.currentTarget.setPointerCapture(e.pointerId);
            e.preventDefault();
          }}
          onPointerMove={(e) => {
            if (drag.current?.field === index)
              update(
                index,
                drag.current.width + e.clientX - drag.current.x,
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
            if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
              e.preventDefault();
              const base = measure(e.currentTarget);
              update(
                index,
                base[index] + (e.key === "ArrowRight" ? 20 : -20),
                base,
              );
            }
          }}
        />
      );
    },
  };
}
