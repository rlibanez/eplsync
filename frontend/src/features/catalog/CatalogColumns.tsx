import { useState } from "react";
import { ActionIcon, Button, Checkbox, Popover } from "@mantine/core";
import { ArrowUp, ArrowDown, Columns3, GripVertical } from "lucide-react";
import { useTranslation } from "react-i18next";
export const columnLabels = {
  selection: "selection.column",
  title: "catalog.book",
  author: "catalog.author",
  collection: "filters.collection",
  genres: "filters.genres",
  pages: "filters.pages",
  publicationYear: "filters.years",
  language: "catalog.language",
  eplId: "filters.eplId",
  revision: "catalog.revision",
  status: "filters.status",
  publicationStatus: "filters.publicationStatus",
  publicationDate: "filters.published",
  insertDate: "filters.added",
} as const;
export type Column = keyof typeof columnLabels;
const defaults = Object.keys(columnLabels) as Column[];
type Settings = { order: Column[]; hidden: Column[]; widths: Partial<Record<Column, number>> };
const storageKey = "eplsync.catalog.columns.v1";
function read(): Settings {
  try {
    const data = JSON.parse(localStorage.getItem(storageKey) || "null");
    if (data && Array.isArray(data.order) && Array.isArray(data.hidden)) {
      const order = [...new Set<Column>(data.order.filter((key: Column) => defaults.includes(key)))];
      if (!order.includes("selection")) order.unshift("selection");
      defaults.forEach(key => { if (!order.includes(key)) order.push(key); });
      const hidden = defaults.filter(key => data.hidden.includes(key));
      const widths: Partial<Record<Column, number>> = {};
      defaults.forEach(key => { const value = data.widths?.[key]; if (typeof value === "number" && Number.isFinite(value) && value >= 70 && value <= 1200) widths[key] = value; });
      return { order, hidden: hidden.length === defaults.length ? [] : hidden, widths };
    }
  } catch { /* Unavailable or obsolete preferences use defaults. */ }
  return { order: [...defaults], hidden: [], widths: {} };
}
export function useCatalogColumns() {
  const [settings, setSettings] = useState(read);
  const update = (next: Settings) => {
    setSettings(next);
    try { localStorage.setItem(storageKey, JSON.stringify(next)); } catch { /* Keep working in memory. */ }
  };
  return { settings, update, visible: settings.order.filter(key => !settings.hidden.includes(key)) };
}
export function CatalogColumns({ settings, update }: Pick<ReturnType<typeof useCatalogColumns>, "settings" | "update">) {
  const { t } = useTranslation();
  const [dragged, setDragged] = useState<Column | null>(null);
  const [target, setTarget] = useState<Column | null>(null);
  function move(index: number, direction: number) {
    const order = [...settings.order];
    [order[index], order[index + direction]] = [order[index + direction], order[index]];
    update({ ...settings, order });
  }
  return <Popover position="bottom-end" width={340} trapFocus>
    <Popover.Target><Button fw={400} variant="default" leftSection={<Columns3 size={16} />}>{t("columns.title")}</Button></Popover.Target>
    <Popover.Dropdown className="column-picker">
      {settings.order.map((key, index) => <div className={`column-choice${target === key && dragged !== key ? " drop-target" : ""}`} key={key} data-column={key}
        onDragOver={event => { if (dragged) { event.preventDefault(); event.dataTransfer.dropEffect = "move"; setTarget(key); } }}
        onDrop={event => {
          event.preventDefault();
          if (dragged && dragged !== key) {
            const order = settings.order.filter(column => column !== dragged);
            order.splice(index, 0, dragged);
            update({ ...settings, order });
          }
          setDragged(null); setTarget(null);
        }}>
        <span className="column-drag" draggable title={t("columns.drag", { column: t(columnLabels[key]) })}
          onDragStart={event => { setDragged(key); event.dataTransfer.effectAllowed = "move"; event.dataTransfer.setData("text/plain", key); }}
          onDragEnd={() => { setDragged(null); setTarget(null); }}><GripVertical size={16} aria-hidden="true" /></span>
        <Checkbox label={t(columnLabels[key])} checked={!settings.hidden.includes(key)}
          disabled={!settings.hidden.includes(key) && settings.hidden.length === defaults.length - 1}
          onChange={event => update({ ...settings, hidden: event.currentTarget.checked ? settings.hidden.filter(k => k !== key) : [...settings.hidden, key] })} />
        <ActionIcon variant="subtle" disabled={index === 0} aria-label={t("columns.up", { column: t(columnLabels[key]) })} onClick={() => move(index, -1)}><ArrowUp size={16} /></ActionIcon>
        <ActionIcon variant="subtle" disabled={index === defaults.length - 1} aria-label={t("columns.down", { column: t(columnLabels[key]) })} onClick={() => move(index, 1)}><ArrowDown size={16} /></ActionIcon>
      </div>)}
      <Button variant="subtle" onClick={() => update({ order: [...defaults], hidden: [], widths: {} })}>{t("columns.reset")}</Button>
    </Popover.Dropdown>
  </Popover>;
}
