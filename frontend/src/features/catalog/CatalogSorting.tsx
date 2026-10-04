import { ActionIcon, Button, Popover, Select } from "@mantine/core";
import { ArrowDown, ArrowUp, ArrowUpDown, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { columnLabels } from "./CatalogColumns";
import { sorts } from "../../api/catalog";

export function CatalogSorting({
  ordering,
  onChange,
}: {
  ordering: string[];
  onChange: (values: string[]) => void;
}) {
  const { t } = useTranslation();
  const labels = columnLabels;
  const fields = Object.keys(labels).filter((key) => key !== "selection");
  function move(index: number, offset: number) {
    const next = [...ordering];
    [next[index], next[index + offset]] = [next[index + offset], next[index]];
    onChange(next);
  }
  const available = fields.filter(
    (field) => !ordering.some((value) => value.startsWith(field + ",")),
  );
  return (
    <Popover position="bottom-end" shadow="md">
      <Popover.Target>
        <Button
          variant="default"
          fw={400}
          leftSection={<ArrowUpDown size={16} />}
        >
          {t("sorting.title")}
          {ordering.length > 1 ? ` (${ordering.length})` : ""}
        </Button>
      </Popover.Target>
      <Popover.Dropdown className="catalog-sorting-panel">
        <p className="muted">{t("sorting.help")}</p>
        {ordering.map((value, index) => {
          const [field, direction] = value.split(",");
          return (
            <div className="catalog-sorting-row" key={field}>
              <span>{index + 1}.</span>
              <Select
                aria-label={t("sorting.field", { index: index + 1 })}
                value={field}
                allowDeselect={false}
                data={[field, ...available].map((key) => ({
                  value: key,
                  label: t(labels[key as keyof typeof labels]),
                }))}
                onChange={(next) =>
                  next &&
                  onChange(
                    ordering.map((old, i) =>
                      i === index ? `${next},${direction}` : old,
                    ),
                  )
                }
              />
              <Select
                aria-label={t("sorting.direction", { index: index + 1 })}
                value={direction}
                allowDeselect={false}
                data={["asc", "desc"].map((dir) => ({
                  value: dir,
                  label: t(`sorts.${sorts[`${field},${dir}`]}`)
                    .split(" · ")
                    .slice(1)
                    .join(" · "),
                }))}
                onChange={(next) =>
                  next &&
                  onChange(
                    ordering.map((old, i) =>
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
                disabled={index === ordering.length - 1}
                aria-label={t("sorting.down", { index: index + 1 })}
                onClick={() => move(index, 1)}
              >
                <ArrowDown size={16} />
              </ActionIcon>
              <ActionIcon
                variant="subtle"
                disabled={ordering.length === 1}
                aria-label={t("sorting.remove", { index: index + 1 })}
                onClick={() => onChange(ordering.filter((_, i) => i !== index))}
              >
                <X size={16} />
              </ActionIcon>
            </div>
          );
        })}
        <div className="catalog-sorting-actions">
          <Button
            variant="light"
            disabled={!available.length}
            onClick={() => onChange([...ordering, `${available[0]},asc`])}
          >
            {t("sorting.add")}
          </Button>
          <Button variant="subtle" onClick={() => onChange(["title,asc"])}>
            {t("sorting.reset")}
          </Button>
        </div>
      </Popover.Dropdown>
    </Popover>
  );
}
