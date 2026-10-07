import { useState } from "react";
import { ActionIcon, Button, Checkbox, Popover, Select } from "@mantine/core";
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  Columns3,
  GripVertical,
  X,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import type { TableColumn } from "./DownloadTable";
export interface ColumnSettings {
  order: string[];
  hidden: string[];
  widths: Record<string, number>;
}
export const ordering = (sort: string) => sort.split(";");
export const sortQuery = (sort: string) =>
  ordering(sort)
    .map((value) => `sort=${encodeURIComponent(value)}`)
    .join("&");
export function TableSorting({
  columns,
  sort,
  onChange,
  defaults,
}: {
  columns: TableColumn[];
  sort: string;
  onChange: (sort: string) => void;
  defaults: string;
}) {
  const { t } = useTranslation();
  const values = ordering(sort);
  const available = columns.filter(
    (column) => !values.some((value) => value.startsWith(column.field + ",")),
  );
  function change(next: string[]) {
    onChange(next.join(";"));
  }
  function move(index: number, offset: number) {
    const next = [...values];
    [next[index], next[index + offset]] = [next[index + offset], next[index]];
    change(next);
  }
  return (
    <Popover position="bottom-end" shadow="md">
      <Popover.Target>
        <Button
          variant="default"
          fw={400}
          leftSection={<ArrowUpDown size={16} />}
        >
          {t("sorting.title")}
          {values.length > 1 ? ` (${values.length})` : ""}
        </Button>
      </Popover.Target>
      <Popover.Dropdown className="catalog-sorting-panel">
        <p className="muted">{t("sorting.help")}</p>
        {values.map((value, index) => {
          const [field, direction] = value.split(",");
          return (
            <div className="catalog-sorting-row" key={field}>
              <span>{index + 1}.</span>
              <Select
                aria-label={t("sorting.field", { index: index + 1 })}
                allowDeselect={false}
                value={field}
                data={[
                  columns.find((column) => column.field === field)!,
                  ...available,
                ]
                  .filter(Boolean)
                  .map((column) => ({
                    value: column.field,
                    label: t(column.label),
                  }))}
                onChange={(next) =>
                  next &&
                  change(
                    values.map((old, i) =>
                      i === index ? `${next},${direction}` : old,
                    ),
                  )
                }
              />
              <Select
                aria-label={t("sorting.direction", { index: index + 1 })}
                allowDeselect={false}
                value={direction}
                data={[
                  { value: "asc", label: t("tableControls.ascending") },
                  { value: "desc", label: t("tableControls.descending") },
                ]}
                onChange={(next) =>
                  next &&
                  change(
                    values.map((old, i) =>
                      i === index ? `${field},${next}` : old,
                    ),
                  )
                }
              />
              <ActionIcon
                variant="subtle"
                disabled={index === 0}
                aria-label={t("sorting.up", { index: index + 1 })}
                onClick={() => move(index, -1)}
              >
                <ArrowUp size={16} />
              </ActionIcon>
              <ActionIcon
                variant="subtle"
                disabled={index === values.length - 1}
                aria-label={t("sorting.down", { index: index + 1 })}
                onClick={() => move(index, 1)}
              >
                <ArrowDown size={16} />
              </ActionIcon>
              <ActionIcon
                variant="subtle"
                disabled={values.length === 1}
                aria-label={t("sorting.remove", { index: index + 1 })}
                onClick={() => change(values.filter((_, i) => i !== index))}
              >
                <X size={16} />
              </ActionIcon>
            </div>
          );
        })}
        <div className="catalog-sorting-actions">
          <Button
            variant="light"
            disabled={!available.length || values.length >= 8}
            onClick={() => change([...values, `${available[0].field},asc`])}
          >
            {t("sorting.add")}
          </Button>
          <Button variant="subtle" onClick={() => onChange(defaults)}>
            {t("sorting.reset")}
          </Button>
        </div>
      </Popover.Dropdown>
    </Popover>
  );
}
export function TableColumns({
  columns,
  settings,
  update,
}: {
  columns: TableColumn[];
  settings: ColumnSettings;
  update: (next: ColumnSettings) => void;
}) {
  const { t } = useTranslation();
  const [dragged, setDragged] = useState<string | null>(null),
    [target, setTarget] = useState<string | null>(null);
  const label = (key: string) =>
    t(columns.find((column) => column.field === key)!.label);
  function move(index: number, offset: number) {
    const order = [...settings.order];
    [order[index], order[index + offset]] = [
      order[index + offset],
      order[index],
    ];
    update({ ...settings, order });
  }
  return (
    <Popover position="bottom-end" width={340} trapFocus>
      <Popover.Target>
        <Button fw={400} variant="default" leftSection={<Columns3 size={16} />}>
          {t("columns.title")}
        </Button>
      </Popover.Target>
      <Popover.Dropdown className="column-picker">
        {settings.order.map((key, index) => (
          <div
            className={`column-choice${target === key && dragged !== key ? " drop-target" : ""}`}
            key={key}
            data-column={key}
            onDragOver={(event) => {
              if (dragged) {
                event.preventDefault();
                event.dataTransfer.dropEffect = "move";
                setTarget(key);
              }
            }}
            onDrop={(event) => {
              event.preventDefault();
              if (dragged && dragged !== key) {
                const order = settings.order.filter(
                  (field) => field !== dragged,
                );
                order.splice(index, 0, dragged);
                update({ ...settings, order });
              }
              setDragged(null);
              setTarget(null);
            }}
          >
            <span
              className="column-drag"
              draggable
              title={t("columns.drag", { column: label(key) })}
              onDragStart={(event) => {
                setDragged(key);
                event.dataTransfer.effectAllowed = "move";
                event.dataTransfer.setData("text/plain", key);
              }}
              onDragEnd={() => {
                setDragged(null);
                setTarget(null);
              }}
            >
              <GripVertical size={16} aria-hidden="true" />
            </span>
            <Checkbox
              label={label(key)}
              checked={!settings.hidden.includes(key)}
              disabled={
                !settings.hidden.includes(key) &&
                settings.hidden.length === columns.length - 1
              }
              onChange={(event) =>
                update({
                  ...settings,
                  hidden: event.currentTarget.checked
                    ? settings.hidden.filter((field) => field !== key)
                    : [...settings.hidden, key],
                })
              }
            />
            <ActionIcon
              variant="subtle"
              disabled={index === 0}
              aria-label={t("columns.up", { column: label(key) })}
              onClick={() => move(index, -1)}
            >
              <ArrowUp size={16} />
            </ActionIcon>
            <ActionIcon
              variant="subtle"
              disabled={index === columns.length - 1}
              aria-label={t("columns.down", { column: label(key) })}
              onClick={() => move(index, 1)}
            >
              <ArrowDown size={16} />
            </ActionIcon>
          </div>
        ))}
        <Button
          variant="subtle"
          onClick={() =>
            update({
              order: columns.map((column) => column.field),
              hidden: [],
              widths: {},
            })
          }
        >
          {t("columns.reset")}
        </Button>
      </Popover.Dropdown>
    </Popover>
  );
}
