import { useTranslation } from "react-i18next";
export const ROW_NUMBER = "rowNumber";
export const rowNumberWidth = (last: number) => Math.max(36, String(Math.max(1, Math.trunc(last))).length * 10 + 20);
export function RowNumberHeading() {
  const { t } = useTranslation();
  return (
    <th className="row-number" scope="col" title={t("columns.rowNumberHelp")}>
      {t("columns.rowNumber")}
    </th>
  );
}
export function RowNumberCell({ value }: { value: number }) {
  return (
    <td className="row-number" data-row-number={value}>
      {value}
    </td>
  );
}
