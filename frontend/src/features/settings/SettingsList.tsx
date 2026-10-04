import { useId, type ReactNode } from "react";
import { ActionIcon, Button, TextInput } from "@mantine/core";
import { Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

export function SettingsList({
  label,
  name,
  values,
  onChange,
  disabled,
  modified,
}: {
  label: ReactNode;
  name: string;
  values: string[];
  onChange: (values: string[]) => void;
  disabled: boolean;
  modified: boolean;
}) {
  const { t } = useTranslation();
  const id = useId();
  return (
    <div role="group" aria-labelledby={id} className="settings-list">
      <div id={id} className="settings-list-label">
        {label}
      </div>
      {values.map((value, index) => (
        <div className="settings-list-row" key={index}>
          <TextInput
            aria-label={`${name} ${index + 1}`}
            value={value}
            disabled={disabled}
            classNames={{
              input: modified ? "send-option-modified" : undefined,
            }}
            onChange={(e) =>
              onChange(
                values.map((item, i) =>
                  i === index ? e.currentTarget.value : item,
                ),
              )
            }
          />
          <ActionIcon
            type="button"
            variant="subtle"
            disabled={disabled}
            aria-label={t("serverSettings.removeRow", {
              name,
              number: index + 1,
            })}
            title={t("serverSettings.removeRow", { name, number: index + 1 })}
            onClick={() => onChange(values.filter((_, i) => i !== index))}
          >
            <Trash2 size={16} />
          </ActionIcon>
        </div>
      ))}
      <Button
        type="button"
        variant="subtle"
        size="compact-sm"
        leftSection={<Plus size={14} />}
        disabled={disabled || values.length >= 100}
        onClick={() => onChange([...values, ""])}
      >
        {t("serverSettings.addRow")}
      </Button>
    </div>
  );
}
