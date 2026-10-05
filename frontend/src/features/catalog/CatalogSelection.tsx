import { copyExportStream } from "./exportTransfer";
import { secureFetch } from "../auth/transport";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNotifications } from "../notifications/Notifications";
import { filterKeys, filterRequest, multiKeys } from "./CatalogFilters";

export function selectionQuery(params: URLSearchParams) {
  const query = new URLSearchParams();
  for (const key of filterKeys)
    for (const value of params.getAll(key)) query.append(key, value);
  return filterRequest(query);
}
export function selectionFilters(
  query: URLSearchParams,
): Record<string, unknown> {
  const filters: Record<string, unknown> = {};
  for (const key of new Set(query.keys())) {
    const numeric = [
      "eplId",
      "revision",
      "pagesFrom",
      "pagesTo",
      "publicationYear",
      "publicationYearFrom",
      "publicationYearTo",
    ].includes(key);
    const values = query
      .getAll(key)
      .map((value) => (numeric ? Number(value) : value));
    filters[key] = multiKeys.includes(key) ? values : values[0];
  }
  return filters;
}
export function useCatalogSelection(query: URLSearchParams) {
  const key = query.toString();
  const [state, setState] = useState({
    key,
    all: false,
    ids: new Set<number>(),
    excluded: new Set<number>(),
  });
  useEffect(() => {
    setState((previous) =>
      previous.key === key
        ? previous
        : { key, all: false, ids: new Set(), excluded: new Set() },
    );
  }, [key]);
  const current =
    state.key === key
      ? state
      : {
          key,
          all: false,
          ids: new Set<number>(),
          excluded: new Set<number>(),
        };
  function clear() {
    setState({ key, all: false, ids: new Set(), excluded: new Set() });
  }
  function toggle(ids: number[], checked: boolean) {
    setState((previous) => {
      const base =
        previous.key === key
          ? previous
          : {
              key,
              all: false,
              ids: new Set<number>(),
              excluded: new Set<number>(),
            };
      const next = {
        ...base,
        ids: new Set(base.ids),
        excluded: new Set(base.excluded),
      };
      for (const id of ids) {
        if (next.all) {
          if (checked) next.excluded.delete(id);
          else next.excluded.add(id);
        } else {
          if (checked) next.ids.add(id);
          else next.ids.delete(id);
        }
      }
      return next;
    });
  }
  return {
    all: current.all,
    clear,
    toggle,
    isSelected: (id: number) =>
      current.all ? !current.excluded.has(id) : current.ids.has(id),
    count: (total: number) =>
      current.all
        ? Math.max(0, total - current.excluded.size)
        : current.ids.size,
    selectAll: () =>
      setState({ key, all: true, ids: new Set(), excluded: new Set() }),
    filters: current.all
      ? { ...selectionFilters(query), excludedIds: [...current.excluded] }
      : { selectedIds: [...current.ids] },
  };
}
interface WritableFile {
  write(data: Blob | Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
}
interface SaveHandle {
  createWritable(): Promise<WritableFile>;
}
type PickerWindow = Window & {
  showSaveFilePicker?: (options: {
    suggestedName: string;
  }) => Promise<SaveHandle>;
};
export function useMagnetExport() {
  const { t } = useTranslation();
  const { notify } = useNotifications();
  const [busy, setBusy] = useState(false);
  async function save(filters: Record<string, unknown>) {
    if (busy) return;
    setBusy(true);
    const picker = (window as PickerWindow).showSaveFilePicker;
    let writable: WritableFile | undefined;
    try {
      // Invoke the picker while the click still has user activation, before the network request.
      const handle = picker
        ? await picker.call(window, { suggestedName: "magnets.txt" })
        : undefined;
      const response = await secureFetch("/api/catalog/magnets/export", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "text/plain" },
        body: JSON.stringify({ filters }),
      });
      if (!response.ok) {
        const error = await response.json().catch(() => undefined);
        throw new Error(typeof error?.details === "string" ? error.details : t("selection.exportError"));
      }
      if (response.headers.get("Content-Length") === "0") throw new Error(t("selection.noMagnets"));
      if (handle) {
        writable = await handle.createWritable();
        await copyExportStream(response, writable, t("selection.noMagnets"));
        await writable.close();
        writable = undefined;
      } else {
        const blob = await response.blob();
        if (!blob.size) throw new Error(t("selection.noMagnets"));
        const url = URL.createObjectURL(blob),
          link = document.createElement("a");
        link.href = url;
        link.download = "magnets.txt";
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      }
    } catch (error) {
      if (writable) await writable.abort().catch(() => {});
      if (!(error instanceof DOMException && error.name === "AbortError"))
        notify({
          title: t("selection.export"),
          message:
            error instanceof Error ? error.message : t("selection.exportError"),
          tone: "error",
        });
    } finally {
      setBusy(false);
    }
  }
  return { save, busy };
}
