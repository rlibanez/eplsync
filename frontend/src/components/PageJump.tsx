import { useEffect, useId, useState } from "react";
import { Button } from "@mantine/core";
import { useTranslation } from "react-i18next";
export function PageJump({
  page,
  totalPages,
  disabled = false,
  onPage,
}: {
  page: number;
  totalPages: number;
  disabled?: boolean;
  onPage: (page: number) => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const [value, setValue] = useState(String(page + 1));
  const pages = Math.max(1, totalPages);
  useEffect(() => setValue(String(page + 1)), [page, totalPages]);
  return (
    <form
      className="page-jump"
      onSubmit={(e) => {
        e.preventDefault();
        const target = Number(value);
        if (
          !disabled &&
          Number.isSafeInteger(target) &&
          target >= 1 &&
          target <= pages
        )
          onPage(target - 1);
      }}
    >
      <label htmlFor={id}>{t("pagination.page")}</label>
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min={1}
        max={pages}
        step={1}
        required
        value={value}
        disabled={disabled || totalPages === 0}
        onChange={(e) => setValue(e.currentTarget.value)}
      />
      <span>{t("pagination.of", { total: pages })}</span>
      <Button
        type="submit"
        variant="default"
        size="xs"
        disabled={disabled || totalPages === 0}
      >
        {t("pagination.go")}
      </Button>
    </form>
  );
}
