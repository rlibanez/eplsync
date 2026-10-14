import { Button } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ReactNode } from "react";
export function SelectionToolbar({
  count,
  onClear,
  selectAll,
  actions,
  controls,
  disabled = false,
}: {
  count: number;
  onClear: () => void;
  selectAll?: ReactNode;
  actions?: ReactNode;
  controls: ReactNode;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="table-toolbar selection-table-toolbar">
      <div className="selection-toolbar-left">
        <div className="selection-toolbar-selection">
          {selectAll}
          {count > 0 && (
            <Button
              variant="subtle"
              size="compact-sm"
              fw={400}
              disabled={disabled}
              onClick={onClear}
            >
              {t("selection.clearCount", { count })}
            </Button>
          )}
        </div>
        <div className="selection-toolbar-actions">{count > 0 && actions}</div>
      </div>
      <div className="catalog-table-controls">{controls}</div>
    </div>
  );
}
