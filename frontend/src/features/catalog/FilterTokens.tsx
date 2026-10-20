import type { ReactNode } from "react";
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
/** Only EPL IDs accept lists; commas in revisions and text retain their meaning. */
export function normalizeFilterValues(
  key: string,
  raw: string,
): string[] | null {
  const parts =
    key === "eplId"
      ? raw
          .trim()
          .split(/[\s,;]+/)
          .filter(Boolean)
      : [raw];
  const values = parts.map((part) => normalizeFilterValue(key, part));
  return values.some((value) => value === null)
    ? null
    : [...new Set(values.filter((value): value is string => !!value))];
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
  allowList = false,
  operator,
}: {
  label: string;
  values: string[];
  draft: string;
  onDraft: (value: string) => void;
  onChange: (values: string[]) => void;
  commit: () => void;
  error?: string;
  suggestionKind?: SuggestionKind;
  allowList?: boolean;
  operator?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <SuggestionInput
      operator={operator}
      label={label}
      onClear={
        values.length || draft
          ? () => {
              onChange([]);
              onDraft("");
            }
          : undefined
      }
      error={error}
      kind={suggestionKind}
      value={draft}
      onChange={onDraft}
      onPaste={
        allowList
          ? (event) => {
              const pasted = event.clipboardData.getData("text");
              if (!/[\r\n]/.test(pasted)) return;
              event.preventDefault();
              const input = event.currentTarget;
              onDraft(
                draft.slice(0, input.selectionStart ?? draft.length) +
                  pasted.replace(/[\r\n]+/g, " ") +
                  draft.slice(input.selectionEnd ?? draft.length),
              );
            }
          : undefined
      }
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
        } else if (
          ((e.key === "Tab" && !e.shiftKey) || (allowList && e.key === " ")) &&
          draft.trim()
        ) {
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
