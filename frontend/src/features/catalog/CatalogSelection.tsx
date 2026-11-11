import { saveCatalogExport } from "./exportTransfer";
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
export function useMagnetExport() {
  const { t } = useTranslation();
  const { notify } = useNotifications();
  const [busy, setBusy] = useState(false);
  async function save(filters: Record<string, unknown>) {
    if (busy) return;
    setBusy(true);
    try {
      await saveCatalogExport(
        "/api/catalog/magnets/export",
        "magnets.txt",
        { filters },
        t("selection.exportError"),
        t("selection.noMagnets"),
        "text/plain",
      );
    } catch (error) {
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
