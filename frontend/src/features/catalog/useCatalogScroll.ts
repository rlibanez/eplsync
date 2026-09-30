import { useLayoutEffect } from "react";

// Restore only after the asynchronous table has its full height, including after
// a reload on the detail page (when the in-memory query cache has been lost).
export function useCatalogScroll(query: string, ready: boolean) {
  const key = `eplsync:catalog-scroll:${query}`;
  useLayoutEffect(() => {
    if (!ready) return;
    try {
      const saved = sessionStorage.getItem(key);
      if (saved === null) return;
      const frame = requestAnimationFrame(() => {
        window.scrollTo(0, Number(saved) || 0);
        sessionStorage.removeItem(key);
      });
      return () => cancelAnimationFrame(frame);
    } catch {
      /* Storage may be disabled by the browser. */
    }
  }, [key, ready]);
  return () => {
    try {
      sessionStorage.setItem(key, String(window.scrollY));
    } catch {
      /* Navigation remains available without storage. */
    }
  };
}
