import { useEffect, useRef, useState } from "react";
import { Button } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { get } from "../api/catalog";
import { Failure } from "./Feedback";
import type { Page } from "../features/downloads/shared";

/** Fetch one bounded page at a time and retain only identifiers. */
export function SelectAllRows<T, I extends string | number>({
  url,
  total,
  identify,
  onSelect,
  loadPage,
}: {
  url: string;
  total: number;
  identify: (row: T) => I;
  onSelect: (ids: I[]) => void;
  loadPage?: (url: string, signal: AbortSignal) => Promise<Page<T>>;
}) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const active = useRef<AbortController | null>(null);
  useEffect(() => {
    setBusy(false);
    setError(null);
    return () => {
      active.current?.abort();
      active.current = null;
    };
  }, [url]);
  const select = async () => {
    active.current?.abort();
    const controller = new AbortController();
    active.current = controller;
    setBusy(true);
    setError(null);
    try {
      const ids = new Set<I>();
      for (let page = 0; ; page++) {
        const [path, query] = url.split("?");
        const params = new URLSearchParams(query);
        params.set("page", String(page));
        params.set("size", "1000");
        const result = await (loadPage ?? get<Page<T>>)(
          `${path}?${params}`,
          controller.signal,
        );
        if (controller.signal.aborted) return;
        result.items.forEach((row) => ids.add(identify(row)));
        if (!result.meta.hasNext || result.items.length === 0) break;
      }
      if (!controller.signal.aborted) onSelect([...ids]);
    } catch (failure) {
      if (!controller.signal.aborted)
        setError(
          failure instanceof Error ? failure : new Error(String(failure)),
        );
    } finally {
      if (active.current === controller) {
        active.current = null;
        setBusy(false);
      }
    }
  };
  return (
    <div className="catalog-toolbar-selection">
      <Button
        variant="subtle"
        size="compact-sm"
        fw={400}
        disabled={!total}
        loading={busy}
        onClick={() => void select()}
      >
        {t("selection.selectAll", {
          count: total,
          formattedCount: String(total),
        })}
      </Button>
      {error && <Failure error={error} retry={() => void select()} />}
    </div>
  );
}
