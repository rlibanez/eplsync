import { Fragment, useState } from "react";
import { Button, TextInput, Select, MultiSelect } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { FilterTokens, normalizeFilterValue } from "./FilterTokens";
import { languages } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";

export const multiKeys = [
  "title",
  "author",
  "collection",
  "genres",
  "eplId",
  "revision",
  "language",
  "status",
  "publicationStatus",
];
export const filterKeys = [
  "title",
  "author",
  "language",
  "eplId",
  "revision",
  "genres",
  "collection",
  "status",
  "publicationStatus",
  "publicationYear",
  "publicationYearFrom",
  "publicationYearTo",
  "publicationDate",
  "publicationDateFrom",
  "publicationDateTo",
  "addedFrom",
  "addedTo",
];
export function localDay(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
export function filterRequest(params: URLSearchParams) {
  const result = new URLSearchParams(params);
  const revisions = result.getAll("revision");
  result.delete("revision");
  revisions.forEach((value) =>
    result.append("revision", normalizeFilterValue("revision", value) ?? value),
  );
  const start = result.get("addedFrom"),
    end = result.get("addedTo");
  result.delete("addedFrom");
  result.delete("addedTo");
  const midnight = (value: string, next = false) => {
    const [y, m, d] = value.split("-").map(Number);
    const date = new Date(y, m - 1, d + (next ? 1 : 0));
    return Number.isNaN(date.getTime()) ? "invalid" : date.toISOString();
  };
  if (start) result.set("insertDateFrom", midnight(start));
  if (start || end)
    result.set("insertDateBefore", midnight(end || localDay(), true));
  if (result.has("publicationDateFrom") && !result.has("publicationDateTo"))
    result.set("publicationDateTo", localDay());
  if (result.has("publicationYearFrom") && !result.has("publicationYearTo"))
    result.set("publicationYearTo", String(new Date().getFullYear()));
  return result;
}
export function CatalogFilters({
  params,
  onChange,
  initiallyOpen = false,
  onToggle,
}: {
  initiallyOpen?: boolean;
  onToggle?: (open: boolean) => void;
  params: URLSearchParams;
  onChange: (params: URLSearchParams) => void;
}) {
  const { t } = useTranslation();
  const { language, status } = useLocale();
  const [values, setValues] = useState(() =>
    Object.fromEntries(
      filterKeys.map((k) => [
        k,
        params.get(k) ||
          (k.startsWith("publicationYear")
            ? params.get("publicationYear")
            : k.startsWith("publicationDate")
              ? params.get("publicationDate")
              : null) ||
          "",
      ]),
    ),
  );
  const [multi, setMulti] = useState<Record<string, string[]>>(() =>
    Object.fromEntries(
      multiKeys.map((key) => [
        key,
        [
          ...new Set(
            params.getAll(key).map((v) => normalizeFilterValue(key, v) ?? v),
          ),
        ],
      ]),
    ),
  );
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const setMultiValue = (key: string, values: string[]) =>
    setMulti((previous) => ({ ...previous, [key]: values }));
  function commit(key: string) {
    const value = normalizeFilterValue(key, drafts[key] || "");
    setErrors((previous) => ({
      ...previous,
      [key]: value === null ? t("filters.invalidValue") : "",
    }));
    if (value === null) return;
    if (value)
      setMulti((previous) => ({
        ...previous,
        [key]: [...new Set([...previous[key], value])],
      }));
    setDrafts((previous) => ({ ...previous, [key]: "" }));
  }
  function tokenField(key: string) {
    return (
      <FilterTokens
        key={key}
        label={t(key === "revision" ? "catalog.revision" : `filters.${key}`)}
        values={multi[key]}
        draft={drafts[key] || ""}
        error={errors[key] || undefined}
        onDraft={(value) => {
          setDrafts((previous) => ({ ...previous, [key]: value }));
          setErrors((previous) => ({ ...previous, [key]: "" }));
        }}
        onChange={(values) => setMultiValue(key, values)}
        commit={() => commit(key)}
      />
    );
  }
  const set = (key: string, value: string | null) =>
    setValues((v) => ({ ...v, [key]: value || "" }));
  const today = localDay();
  const years = Array.from({ length: new Date().getFullYear() + 1 }, (_, i) =>
    String(new Date().getFullYear() - i),
  );
  const ranges = [
    ["publicationYearFrom", "publicationYearTo", "years"],
    ["publicationDateFrom", "publicationDateTo", "published"],
    ["addedFrom", "addedTo", "added"],
  ];
  const [equal, setEqual] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(
      ranges.map(([from, to]) => [
        from,
        Boolean(values[from] && values[from] === values[to]),
      ]),
    ),
  );
  function setRange(
    from: string,
    to: string,
    key: string,
    value: string | null,
  ) {
    if (equal[from])
      setValues((v) => ({ ...v, [from]: value || "", [to]: value || "" }));
    else set(key, value);
  }
  function clearRange(from: string, to: string) {
    setValues((v) => ({ ...v, [from]: "", [to]: "" }));
    setEqual((v) => ({ ...v, [from]: false }));
  }
  return (
    <details
      className="catalog-search panel"
      open={initiallyOpen}
      onToggle={(event) => onToggle?.(event.currentTarget.open)}
    >
      <summary
        className={
          filterKeys.some((key) => params.has(key))
            ? "filters-active"
            : undefined
        }
      >
        {t("filters.heading")}
        {filterKeys.some((k) => params.has(k))
          ? ` · ${t("filters.active")}`
          : ""}
        {filterKeys.some((key) => params.has(key)) && (
          <Button
            type="button"
            variant="subtle"
            size="compact-sm"
            fw={400}
            className="catalog-clear-filters"
            onClick={(event) => {
              event.preventDefault();
              event.stopPropagation();
              const details = event.currentTarget.closest("details");
              onToggle?.(details?.open ?? false);
              const next = new URLSearchParams(params);
              filterKeys.forEach((key) => next.delete(key));
              next.set("page", "0");
              onChange(next);
            }}
          >
            {t("filters.clearApplied")}
          </Button>
        )}
      </summary>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          const merged = { ...multi };
          const invalid: Record<string, string> = {};
          for (const key of multiKeys) {
            const value = normalizeFilterValue(key, drafts[key] || "");
            if (value === null) invalid[key] = t("filters.invalidValue");
            else if (value) merged[key] = [...new Set([...merged[key], value])];
          }
          setErrors(invalid);
          if (Object.keys(invalid).length) return;
          setMulti(merged);
          setDrafts({});
          const next = new URLSearchParams(params);
          filterKeys.forEach((key) => {
            next.delete(key);
            if (values[key] && !multiKeys.includes(key))
              next.set(key, values[key]);
          });
          multiKeys.forEach((key) =>
            merged[key].forEach((value) => next.append(key, value)),
          );
          // Exact filters from directory links become equivalent editable ranges.
          next.delete("publicationYear");
          next.delete("publicationDate");
          next.set("page", "0");
          onChange(next);
        }}
      >
        <div className="catalog-filter-grid">
          {["title", "author", "collection", "genres"].map(tokenField)}
          <MultiSelect
            searchable
            label={t("catalog.language")}
            value={multi.language}
            onChange={(v) => setMultiValue("language", v)}
            clearable
            data={languages.map((value) => ({ value, label: language(value) }))}
          />
          <MultiSelect
            searchable
            label={t("filters.status")}
            value={multi.status}
            onChange={(v) => setMultiValue("status", v)}
            clearable
            data={["DISPONIBLE", "VERIFICADO", "DESCONOCIDO"].map((value) => ({
              value,
              label: status(value),
            }))}
          />
          <MultiSelect
            searchable
            label={t("filters.publicationStatus")}
            value={multi.publicationStatus}
            onChange={(v) => setMultiValue("publicationStatus", v)}
            clearable
            data={["PUBLISHED", "UPDATED", "UNKNOWN"].map((value) => ({
              value,
              label: status(value),
            }))}
          />
          {tokenField("eplId")}
          {tokenField("revision")}
        </div>
        {ranges.map(([from, to, label]) => (
          <fieldset className="catalog-date-range" key={from}>
            <legend>{t(`filters.${label}`)}</legend>
            {[from, to].map((key, index) => (
              <Fragment key={key}>
                {index === 1 && (
                  <Button
                    type="button"
                    className="date-equal"
                    variant={equal[from] ? "filled" : "default"}
                    aria-label={t("filters.equal")}
                    title={t("filters.equal")}
                    aria-pressed={Boolean(equal[from])}
                    onClick={() => {
                      const checked = !equal[from];
                      setEqual((v) => ({ ...v, [from]: checked }));
                      if (checked)
                        setValues((v) => ({
                          ...v,
                          [from]: v[from] || v[to],
                          [to]: v[from] || v[to],
                        }));
                    }}
                  >
                    =
                  </Button>
                )}
                {label === "years" ? (
                  <Select
                    label={t(index ? "filters.to" : "filters.from")}
                    searchable
                    clearable
                    value={values[key] || null}
                    onChange={(v) => setRange(from, to, key, v)}
                    data={years.filter(
                      (y) =>
                        equal[from] ||
                        (index
                          ? !values[from] || +y >= +values[from]
                          : !values[to] || +y <= +values[to]),
                    )}
                  />
                ) : (
                  <TextInput
                    type="date"
                    label={t(index ? "filters.to" : "filters.from")}
                    value={values[key]}
                    onChange={(e) =>
                      setRange(from, to, key, e.currentTarget.value)
                    }
                    min={
                      !equal[from] && index
                        ? values[from] || undefined
                        : undefined
                    }
                    max={!equal[from] && !index ? values[to] || today : today}
                  />
                )}
              </Fragment>
            ))}
            <Button
              variant="subtle"
              size="compact-sm"
              onClick={() => clearRange(from, to)}
            >
              {t("catalog.clear")}
            </Button>
          </fieldset>
        ))}
        <div className="action-row">
          <Button type="submit">{t("catalog.search")}</Button>
          <Button
            variant="subtle"
            onClick={() => {
              setValues(Object.fromEntries(filterKeys.map((key) => [key, ""])));
              setMulti(Object.fromEntries(multiKeys.map((key) => [key, []])));
              setDrafts({});
              setErrors({});
              setEqual({});
              onChange(new URLSearchParams());
            }}
          >
            {t("catalog.clear")}
          </Button>
        </div>
      </form>
    </details>
  );
}
