import { useState, type ReactNode } from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
import { compareText } from "../locales/textOrdering";

export type PreviewOrder = { key: string; descending: boolean } | null;
export function usePreviewSort<T>(rows: T[], resetPage: () => void) {
  const [order, setOrder] = useState<PreviewOrder>(null);
  function toggle(key: string) {
    setOrder((old) => ({
      key,
      descending: old?.key === key && !old.descending,
    }));
    resetPage();
  }
  function sorted(
    value: (row: T, key: string) => string | number | null | undefined,
  ) {
    if (!order) return rows;
    return [...rows].sort((a, b) => {
      const left = value(a, order.key),
        right = value(b, order.key);
      const result =
        left == null
          ? right == null
            ? 0
            : 1
          : right == null
            ? -1
            : typeof left === "number" && typeof right === "number"
              ? left - right
              : compareText(String(left), String(right));
      return order.descending ? -result : result;
    });
  }
  return { order, toggle, sorted };
}
export function PreviewHeading({
  field,
  children,
  order,
  toggle,
}: {
  field: string;
  children: ReactNode;
  order?: PreviewOrder;
  toggle?: (key: string) => void;
}) {
  const active = order?.key === field;
  return (
    <th
      aria-sort={
        active ? (order.descending ? "descending" : "ascending") : undefined
      }
    >
      {toggle ? (
        <button
          type="button"
          className="catalog-sort-heading"
          onClick={() => toggle(field)}
        >
          {children}
          {active &&
            (order.descending ? (
              <ArrowDown size={14} />
            ) : (
              <ArrowUp size={14} />
            ))}
        </button>
      ) : (
        children
      )}
    </th>
  );
}
