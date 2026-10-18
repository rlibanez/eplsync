import {
  useEffect,
  useRef,
  type ChangeEvent,
  type MouseEvent as ReactMouseEvent,
} from "react";

/** Range anchors belong to one visible page and its current ordering/filter scope. */
export function useRangeSelection<T>(
  visible: readonly T[],
  apply: (ids: T[], checked: boolean) => void,
  scope: string = "",
) {
  const anchor = useRef<{ key: string; id: T } | null>(null);
  const key = JSON.stringify([scope, visible]);
  useEffect(() => {
    anchor.current = null;
  }, [key]);
  const select = (id: T, event: ChangeEvent<HTMLInputElement>) => {
    const end = visible.indexOf(id);
    if (end < 0) return;
    const start =
      anchor.current?.key === key ? visible.indexOf(anchor.current.id) : -1;
    const shift = (event.nativeEvent as MouseEvent).shiftKey === true;
    const ids =
      shift && start >= 0
        ? visible.slice(Math.min(start, end), Math.max(start, end) + 1)
        : [id];
    anchor.current = { key, id };
    apply([...ids], event.currentTarget.checked);
  };
  return Object.assign(select, {
    preventTextSelection(event: ReactMouseEvent<HTMLInputElement>) {
      // Shift-mousedown extends the browser text selection before change fires.
      // Suppress that default only on range checkboxes; clicks still toggle them.
      if (event.button === 0 && event.shiftKey) event.preventDefault();
    },
  });
}
