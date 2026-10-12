import { useEffect, useState, useRef } from "react";
import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";

export const pageSizes = [10, 20, 50, 100, 200, 500, 1000];
export function canShowAll(total: number | undefined, maximum = 1000) {
  return total !== undefined && total > 0 && total <= Math.min(1000, maximum);
}

/** Kept in the table's parent so loading or an empty result cannot erase the choice. */
export function usePageSizeChoice(size: number) {
  const [state, setState] = useState({ all: false, lastNumeric: size });
  const previousSize = useRef(size);
  useEffect(() => {
    // URL-driven pagination may render once with the old size after choosing all.
    // Only leave that mode for an external numeric change after size 1000 arrived.
    const numericNavigation = previousSize.current === 1000 && size !== 1000;
    previousSize.current = size;
    if (state.all && !numericNavigation) return;
    if (state.all || state.lastNumeric !== size)
      setState({ all: false, lastNumeric: size });
  }, [size, state.all, state.lastNumeric]);
  return {
    ...state,
    choose: (value: string) =>
      setState((previous) =>
        value === "all"
          ? { ...previous, all: true }
          : { all: false, lastNumeric: Number(value) },
      ),
  };
}
export type PageSizeChoice = ReturnType<typeof usePageSizeChoice>;
export function PageSizeSelect({
  size,
  total,
  onSize,
  choice,
  maximum = 1000,
  label,
  numericLabel,
  disabled,
}: {
  size: number;
  total?: number;
  onSize: (size: number) => void;
  choice: PageSizeChoice;
  maximum?: number;
  label: string;
  numericLabel?: (size: number) => string;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const allowed = canShowAll(total, maximum);
  useEffect(() => {
    if (
      !disabled &&
      choice.all &&
      total !== undefined &&
      (total === 0 || total > Math.min(1000, maximum))
    ) {
      choice.choose(String(choice.lastNumeric));
      onSize(choice.lastNumeric);
    }
  }, [choice, total, maximum, onSize, disabled]);
  return (
    <Select
      className="page-size-select"
      withCheckIcon={false}
      aria-label={label}
      disabled={disabled}
      value={choice.all ? "all" : String(size)}
      data={[
        ...pageSizes
          .filter((value) => value <= maximum)
          .map((value) => ({
            value: String(value),
            label: numericLabel?.(value) ?? String(value),
          })),
        ...(allowed || (choice.all && total === undefined)
          ? [{ value: "all", label: t("pagination.showAll") }]
          : []),
      ]}
      allowDeselect={false}
      onChange={(value) => {
        if (!value) return;
        choice.choose(value);
        onSize(value === "all" ? 1000 : Number(value));
      }}
    />
  );
}
