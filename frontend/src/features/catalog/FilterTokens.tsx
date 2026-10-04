import {
  SuggestionInput,
  type SuggestionKind,
} from "../../components/SuggestionInput";
import { useTranslation } from "react-i18next";
import { Pill } from "@mantine/core";

export function normalizeFilterValue(key: string, raw: string): string | null {
  const value = raw.trim();
  if (!value) return "";
  if (key === "eplId")
    return /^\d+$/.test(value) &&
      Number.isSafeInteger(Number(value)) &&
      Number(value) > 0
      ? String(Number(value))
      : null;
  if (key === "revision") {
    const numeric = value.replace(",", ".");
    return /^\d+(?:\.\d+)?$/.test(numeric) && Number.isFinite(Number(numeric))
      ? String(Number(numeric))
      : null;
  }
  return value.length <= (key === "author" || key === "collection" ? 255 : 512)
    ? value
    : null;
}
export function FilterTokens({
  label,
  values,
  draft,
  onDraft,
  onChange,
  commit,
  error,
  suggestionKind,
}: {
  label: string;
  values: string[];
  draft: string;
  onDraft: (value: string) => void;
  onChange: (values: string[]) => void;
  commit: () => void;
  error?: string;
  suggestionKind?: SuggestionKind;
}) {
  const { t } = useTranslation();
  return (
    <SuggestionInput
      label={label}
      error={error}
      kind={suggestionKind}
      value={draft}
      onChange={onDraft}
      excluded={values}
      onSelect={(value) => {
        onChange([...new Set([...values, value])]);
        onDraft("");
      }}
      onKeyDown={(e) => {
        if (e.nativeEvent.isComposing) return;
        if (
          (e.key === "Backspace" || e.key === "Delete") &&
          !draft &&
          values.length
        ) {
          e.preventDefault();
          onChange(values.slice(0, -1));
        } else if (e.key === "Enter") {
          e.preventDefault();
          if (draft.trim()) commit();
          else e.currentTarget.form?.requestSubmit();
        } else if (e.key === "Tab" && !e.shiftKey && draft.trim()) {
          e.preventDefault();
          commit();
        }
      }}
    >
      {values.map((value) => (
        <Pill
          key={value}
          withRemoveButton
          removeButtonProps={{
            "aria-hidden": false,
            "aria-label": t("filters.removeValue", { value }),
            tabIndex: 0,
          }}
          onRemove={() => onChange(values.filter((v) => v !== value))}
        >
          {value}
        </Pill>
      ))}
    </SuggestionInput>
  );
}
