import { detectedSelectionParams } from "./detectedSelection";
import { DetectedValues } from "./DetectedValues";
import { Download, Library } from "lucide-react";
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  useId,
  type ReactNode,
} from "react";
import {
  Combobox,
  InputBase,
  CloseButton,
  useCombobox,
  Badge,
  Tooltip,
  Button,
  Checkbox,
  Group,
  NumberInput,
  Select,
  Stack,
  Text,
} from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/Auth";
import { get, type Book } from "../../api/catalog";
import { Failure } from "../../components/Feedback";
import { useLocale } from "../../locales/useLocale";
import type { Page } from "../downloads/shared";

export const criteria = [
  "AUTHOR",
  "COLLECTION",
  "GENRE",
  "LANGUAGE",
  "YEAR",
] as const;
export type Criterion = (typeof criteria)[number];
export type Choice = { all: boolean; values: string[]; excluded?: string[] };
export const discoveryKeys = [
  "discoveryIgnoreAnthologies",
  "discoveryMaxAuthors",
  "discoverySource",
  "discoveryCriterion",
  "discoveryCriteria",
  "discoveryMatch",
  "discoveryValues",
  "discoveryExcludedValues",
  "discoveryExcludeDownloads",
  "discoveryExcludeCalibre",
  "discoveryExcludePending",
];
export type DiscoveryPreferences = {
  source: string;
  criteria: Criterion[];
  match: "ALL" | "ANY";
  choices: Partial<Record<Criterion, Choice>>;
  downloads: boolean;
  calibre: boolean;
  pending: boolean;
  ignoreAnthologies?: boolean;
  maxAuthors?: number;
};
export function defaultDiscoveryPreferences(
  downloads: boolean,
  calibre: boolean,
): DiscoveryPreferences {
  return {
    source: downloads ? "DOWNLOADS" : "CALIBRE",
    criteria: ["AUTHOR"],
    match: "ALL",
    choices: {},
    downloads,
    calibre,
    pending: true,
  };
}
type Stored = Partial<DiscoveryPreferences> & {
  criterion?: string;
  all?: boolean;
  values?: string[];
};
export function discoveryPreferences(userId?: string): Stored {
  try {
    return (
      JSON.parse(
        localStorage.getItem(`eplsync.discovery.${userId ?? ""}.v1`) ?? "{}",
      ) ?? {}
    );
  } catch {
    return {};
  }
}
const validCriteria = (items: unknown): Criterion[] =>
  Array.isArray(items)
    ? criteria.filter((criterion) => items.includes(criterion))
    : [];
const validValues = (items: unknown) =>
  Array.isArray(items)
    ? [
        ...new Set(
          items.filter(
            (value): value is string =>
              typeof value === "string" &&
              value.trim().length > 0 &&
              value.length <= 512,
          ),
        ),
      ].slice(0, 10000)
    : [];
const choiceFor = (
  config: DiscoveryPreferences,
  criterion: Criterion,
): Choice => config.choices[criterion] ?? { all: true, values: [] };
export function applyDiscoveryConfiguration(
  next: URLSearchParams,
  config: DiscoveryPreferences,
) {
  const selectedCriteria = validCriteria(config.criteria);
  if (
    !selectedCriteria.length ||
    (config.maxAuthors !== undefined &&
      (!Number.isInteger(config.maxAuthors) || config.maxAuthors < 1)) ||
    selectedCriteria.some(
      (criterion) =>
        choiceFor(config, criterion).values.length > 10000 ||
        (choiceFor(config, criterion).excluded?.length ?? 0) > 10000,
    )
  )
    return false;
  discoveryKeys.forEach((key) => next.delete(key));
  next.set("discoverySource", config.source);
  selectedCriteria.forEach((criterion) =>
    next.append("discoveryCriteria", criterion),
  );
  if (config.ignoreAnthologies) next.set("discoveryIgnoreAnthologies", "true");
  if (config.maxAuthors !== undefined)
    next.set("discoveryMaxAuthors", String(config.maxAuthors));
  next.set("discoveryMatch", config.match);
  next.set("discoveryExcludePending", String(config.pending));
  for (const criterion of selectedCriteria) {
    const choice = choiceFor(config, criterion);
    if (!choice.all) {
      (choice.values.length ? choice.values : [""]).forEach((value) =>
        next.append("discoveryValues", `${criterion}:${value}`),
      );
    } else {
      (choice.excluded ?? []).forEach((value) =>
        next.append("discoveryExcludedValues", `${criterion}:${value}`),
      );
    }
  }
  return true;
}
export function discoveryConfigurationSignature(config: DiscoveryPreferences) {
  const params = new URLSearchParams();
  if (!applyDiscoveryConfiguration(params, config)) return "invalid";
  return JSON.stringify(
    [...params.entries()].sort(
      ([a, av], [b, bv]) => a.localeCompare(b) || av.localeCompare(bv),
    ),
  );
}
export function DiscoveryPanel({
  params,
  onSearch,
  onClear,
  resetDefaults = false,
  presence,
  onPresenceChange,
  captureFilters,
  configuration,
  savedSearches,
  advancedDirty = false,
}: {
  params: URLSearchParams;
  onSearch: (params: URLSearchParams) => void;
  onClear: () => void;
  resetDefaults?: boolean;
  presence: { torrent: string; calibre: string };
  onPresenceChange: (library: "torrent" | "calibre", value: string) => void;
  captureFilters: () => URLSearchParams | null;
  configuration: { current: DiscoveryPreferences | null };
  savedSearches: ReactNode;
  advancedDirty?: boolean;
}) {
  const { can, user } = useAuth();
  const { t } = useTranslation();
  const storage = `eplsync.discovery.${user?.id ?? ""}.v1`;
  const sources = [
    can("TORRENT_SYNC") && "DOWNLOADS",
    can("CALIBRE_READ") && "CALIBRE",
    can("TORRENT_SYNC") && can("CALIBRE_READ") && "BOTH",
  ].filter(Boolean) as string[];
  const [preferences, setPreferences] = useState<DiscoveryPreferences>(() => {
    if (resetDefaults)
      return defaultDiscoveryPreferences(
        can("TORRENT_SYNC"),
        can("CALIBRE_READ"),
      );
    const saved = discoveryPreferences(user?.id);
    const fromUrl = params.has("discoverySource");
    const source = params.get("discoverySource") ?? saved.source;
    const restored = validCriteria(
      fromUrl
        ? params.has("discoveryCriteria")
          ? params.getAll("discoveryCriteria")
          : [params.get("discoveryCriterion") ?? "AUTHOR"]
        : (saved.criteria ?? [saved.criterion ?? "AUTHOR"]),
    );
    const selected = restored.length ? restored : (["AUTHOR"] as Criterion[]);
    const choices: Partial<Record<Criterion, Choice>> = {};
    for (const criterion of criteria) {
      if (fromUrl) {
        const values = params
          .getAll("discoveryValues")
          .filter((value) => value.startsWith(`${criterion}:`))
          .map((value) => value.slice(criterion.length + 1));
        if (selected.length === 1 && selected[0] === criterion)
          values.push(
            ...params
              .getAll("discoveryValues")
              .filter(
                (value) =>
                  !/^(AUTHOR|COLLECTION|GENRE|LANGUAGE|YEAR):/.test(value),
              ),
          );
        choices[criterion] = {
          all: !values.length,
          values: validValues(values),
          excluded: validValues(
            params
              .getAll("discoveryExcludedValues")
              .filter((value) => value.startsWith(`${criterion}:`))
              .map((value) => value.slice(criterion.length + 1)),
          ),
        };
      } else {
        const old =
          saved.choices?.[criterion] ??
          (criterion === (saved.criterion ?? "AUTHOR")
            ? { all: saved.all !== false, values: saved.values ?? [] }
            : undefined);
        choices[criterion] = {
          all: old?.all !== false,
          values: validValues(old?.values),
          excluded: validValues(
            old && "excluded" in old ? old.excluded : undefined,
          ),
        };
      }
    }
    return {
      source: sources.includes(source ?? "") ? source! : sources[0],
      criteria: selected,
      match:
        (fromUrl ? params.get("discoveryMatch") : saved.match) === "ANY"
          ? "ANY"
          : "ALL",
      choices,
      ignoreAnthologies: fromUrl
        ? params.get("discoveryIgnoreAnthologies") === "true"
        : saved.ignoreAnthologies === true,
      maxAuthors: (() => {
        const value = fromUrl
          ? Number(params.get("discoveryMaxAuthors"))
          : saved.maxAuthors;
        return typeof value === "number" && Number.isInteger(value) && value > 0
          ? value
          : undefined;
      })(),
      downloads: presence.torrent === "ABSENT",
      calibre: presence.calibre === "ABSENT",
      pending: fromUrl
        ? params.get("discoveryExcludePending") === "true"
        : saved.pending !== false,
    };
  });
  const initialConfiguration = useRef(
    discoveryConfigurationSignature(preferences),
  );
  const hasUnappliedChanges =
    params.has("discoverySource") &&
    (advancedDirty ||
      discoveryConfigurationSignature(preferences) !==
        initialConfiguration.current);
  useEffect(() => {
    setPreferences((previous) => ({
      ...previous,
      downloads: presence.torrent === "ABSENT",
      calibre: presence.calibre === "ABSENT",
    }));
  }, [presence.torrent, presence.calibre]);
  useEffect(() => {
    configuration.current = preferences;
  }, [preferences, configuration]);
  useEffect(() => {
    try {
      localStorage.setItem(storage, JSON.stringify(preferences));
    } catch {
      /* Optional persistence. */
    }
  }, [preferences, storage]);
  const change = (patch: Partial<DiscoveryPreferences>) =>
    setPreferences((previous) => ({ ...previous, ...patch }));
  const invalid = !preferences.criteria.length;
  return (
    <section className="panel">
      <Stack px={22} py="lg">
        <Text c="dimmed">{t("discovery.help")}</Text>
        <div className="catalog-filter-grid discovery-filters">
          <Select
            label={t("discovery.origin")}
            data={sources.map((value) => ({
              value,
              label: t(`discovery.sources.${value}`),
            }))}
            allowDeselect={false}
            value={preferences.source}
            onChange={(source) => {
              if (source) change({ source, choices: {} });
            }}
          />
          <CriteriaPicker
            value={preferences.criteria}
            onChange={(values) => change({ criteria: values })}
          />
          <Select
            label={
              <Tooltip
                label={t("discovery.combineHelp")}
                events={{ hover: true, focus: true, touch: false }}
                multiline
                w={300}
                withArrow
              >
                <span tabIndex={0} style={{ cursor: "help" }}>
                  {t("discovery.combine")}
                </span>
              </Tooltip>
            }
            data={["ALL", "ANY"].map((value) => ({
              value,
              label: t(`discovery.modes.${value}`),
            }))}
            allowDeselect={false}
            value={preferences.match}
            onChange={(value) => {
              if (value === "ALL" || value === "ANY") change({ match: value });
            }}
          />
          <CheckboxPicker
            label={t("discovery.exclusions")}
            placeholder={t("discovery.noExclusions")}
            className="discovery-exclusions-picker"
            options={[
              {
                value: "DOWNLOADS",
                label: t("discovery.sources.DOWNLOADS"),
                disabled: !can("TORRENT_SYNC"),
              },
              {
                value: "CALIBRE",
                label: t("discovery.sources.CALIBRE"),
                disabled: !can("CALIBRE_READ"),
              },
              { value: "PENDING", label: t("discovery.pending") },
            ]}
            value={[
              ...(presence.torrent === "ABSENT" ? ["DOWNLOADS"] : []),
              ...(presence.calibre === "ABSENT" ? ["CALIBRE"] : []),
              ...(preferences.pending ? ["PENDING"] : []),
            ]}
            onChange={(selected) => {
              if (
                can("TORRENT_SYNC") &&
                selected.includes("DOWNLOADS") !==
                  (presence.torrent === "ABSENT")
              )
                onPresenceChange(
                  "torrent",
                  selected.includes("DOWNLOADS") ? "ABSENT" : "",
                );
              if (
                can("CALIBRE_READ") &&
                selected.includes("CALIBRE") !== (presence.calibre === "ABSENT")
              )
                onPresenceChange(
                  "calibre",
                  selected.includes("CALIBRE") ? "ABSENT" : "",
                );
              change({ pending: selected.includes("PENDING") });
            }}
          />
        </div>
        {preferences.criteria.length > 0 && (
          <div className="catalog-filter-grid discovery-criteria-layout">
            <div className="discovery-criteria-frame">
              <table
                className="discovery-criteria-table"
                aria-label={t("discovery.criteriaTable")}
              >
                <colgroup>
                  <col className="criterion-column" />
                  <col className="count-column" />
                  <col className="selected-column" />
                </colgroup>
                <thead>
                  <tr>
                    <th scope="col">{t("discovery.criterionColumn")}</th>
                    <th scope="col">{t("discovery.availableHeader")}</th>
                    <th scope="col">{t("discovery.selectedHeader")}</th>
                  </tr>
                </thead>
                {preferences.criteria.map((criterion) => (
                  <CriterionValues
                    key={`${preferences.source}:${criterion}`}
                    source={preferences.source}
                    criterion={criterion}
                    choice={choiceFor(preferences, criterion)}
                    ignoreAnthologies={preferences.ignoreAnthologies === true}
                    maxAuthors={preferences.maxAuthors}
                    onChange={(choice) =>
                      setPreferences((previous) => ({
                        ...previous,
                        choices: { ...previous.choices, [criterion]: choice },
                      }))
                    }
                  />
                ))}
              </table>
            </div>
            {preferences.criteria.includes("AUTHOR") && (
              <AuthorDetectionOptions
                source={preferences.source}
                ignoreAnthologies={preferences.ignoreAnthologies === true}
                maxAuthors={preferences.maxAuthors}
                onAuthorOptions={(options) =>
                  setPreferences((previous) => ({ ...previous, ...options }))
                }
              />
            )}
          </div>
        )}
        <div className="catalog-search-footer" style={{ marginTop: 0 }}>
          <div className="action-row">
            <Button
              disabled={invalid}
              onClick={() => {
                const next = captureFilters();
                if (next) onSearch(next);
              }}
            >
              {t("catalog.search")}
            </Button>
            {hasUnappliedChanges && (
              <Text role="status" size="sm" c="dimmed">
                {t("discovery.unappliedChanges")}
              </Text>
            )}
            <Button variant="subtle" onClick={onClear}>
              {t("catalog.clear")}
            </Button>
          </div>
          {savedSearches}
        </div>
      </Stack>
    </section>
  );
}
function CriteriaPicker({
  value,
  onChange,
}: {
  value: Criterion[];
  onChange: (value: Criterion[]) => void;
}) {
  const { t } = useTranslation();
  return (
    <CheckboxPicker
      label={t("discovery.criterion")}
      placeholder={t("discovery.chooseCriteria")}
      className="discovery-criteria-input"
      value={value}
      options={criteria.map((criterion) => ({
        value: criterion,
        label: t(`discovery.criteria.${criterion}`),
      }))}
      onChange={(selected) => onChange(validCriteria(selected))}
    />
  );
}
function useFittingSummary(labels: string[]) {
  const input = useRef<HTMLInputElement>(null);
  const full = labels.join(", ");
  const [summary, setSummary] = useState(full);
  useLayoutEffect(() => {
    const element = input.current;
    if (!element) return;
    const context = document.createElement("canvas").getContext("2d");
    if (!context) return;
    let disposed = false;
    const update = () => {
      if (disposed) return;
      const style = getComputedStyle(element);
      context.font =
        style.font ||
        `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
      const spacing = parseFloat(style.letterSpacing) || 0;
      const available = Math.max(
        0,
        element.clientWidth -
          (parseFloat(style.paddingLeft) || 0) -
          (parseFloat(style.paddingRight) || 0),
      );
      const fits = (text: string) =>
        context.measureText(text).width + spacing * text.length <= available;
      let next = full;
      if (!fits(full)) {
        next = `+${labels.length}`;
        for (let count = labels.length - 1; count > 0; count--) {
          const candidate = `${labels.slice(0, count).join(", ")} +${labels.length - count}`;
          if (fits(candidate)) {
            next = candidate;
            break;
          }
        }
      }
      setSummary((previous) => (previous === next ? previous : next));
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    void document.fonts.ready.then(update);
    document.fonts.addEventListener("loadingdone", update);
    return () => {
      disposed = true;
      observer.disconnect();
      document.fonts.removeEventListener("loadingdone", update);
    };
  }, [full]);
  return { input, summary, full };
}
type PickerOption = { value: string; label: string; disabled?: boolean };
function CheckboxPicker({
  value,
  onChange,
  label,
  placeholder,
  options,
  className,
}: {
  value: string[];
  onChange: (value: string[]) => void;
  label: string;
  placeholder: string;
  options: PickerOption[];
  className: string;
}) {
  const { t } = useTranslation();
  const combobox = useCombobox({
    onDropdownClose: () => combobox.resetSelectedOption(),
  });
  const labels = value.map(
    (item) => options.find((option) => option.value === item)?.label ?? item,
  );
  const { input, summary, full } = useFittingSummary(labels);
  return (
    <Combobox
      store={combobox}
      onOptionSubmit={(raw) => {
        if (options.find((option) => option.value === raw)?.disabled) return;
        onChange(
          value.includes(raw)
            ? value.filter((item) => item !== raw)
            : [...value, raw],
        );
      }}
    >
      <Tooltip
        label={full}
        disabled={!value.length || combobox.dropdownOpened}
        events={{ hover: true, focus: true, touch: false }}
        multiline
        w={300}
        withArrow
      >
        <div>
          <Combobox.Target withExpandedAttribute>
            <InputBase
              ref={input}
              className={`discovery-summary-input ${className}`}
              label={label}
              value={summary}
              placeholder={placeholder}
              readOnly
              pointer
              rightSectionWidth={60}
              rightSectionPointerEvents="none"
              onClick={() => combobox.toggleDropdown()}
              onBlur={() => combobox.closeDropdown()}
              onKeyDown={(event) => {
                if (event.key === " " && !combobox.dropdownOpened) {
                  event.preventDefault();
                  combobox.openDropdown();
                }
              }}
              rightSection={
                <Group gap={0} wrap="nowrap" justify="flex-end" w="100%">
                  {value.length > 0 && (
                    <CloseButton
                      size="sm"
                      style={{ pointerEvents: "auto" }}
                      aria-label={`${t("catalog.clear")}: ${label}`}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={(event) => {
                        event.stopPropagation();
                        onChange([]);
                        combobox.closeDropdown();
                      }}
                    />
                  )}
                  <span className="discovery-picker-chevron">
                    <Combobox.Chevron size="sm" />
                  </span>
                </Group>
              }
            />
          </Combobox.Target>
        </div>
      </Tooltip>
      <Combobox.Dropdown>
        <Combobox.Options aria-multiselectable="true">
          {options.map((option) => (
            <Combobox.Option
              key={option.value}
              value={option.value}
              disabled={option.disabled}
              active={value.includes(option.value)}
              aria-selected={value.includes(option.value)}
            >
              <Group gap="sm">
                <Checkbox
                  checked={value.includes(option.value)}
                  disabled={option.disabled}
                  readOnly
                  tabIndex={-1}
                  aria-hidden="true"
                  style={{ pointerEvents: "none" }}
                />
                <span>{option.label}</span>
              </Group>
            </Combobox.Option>
          ))}
        </Combobox.Options>
      </Combobox.Dropdown>
    </Combobox>
  );
}
type Value = { value: string; label: string; counts: Record<string, number> };
function CriterionValues({
  source,
  criterion,
  choice,
  onChange,
  ignoreAnthologies,
  maxAuthors,
}: {
  ignoreAnthologies: boolean;
  maxAuthors?: number;
  source: string;
  criterion: Criterion;
  choice: Choice;
  onChange: (choice: Choice) => void;
}) {
  const { t } = useTranslation();
  const { number } = useLocale();
  const rowId = useId();
  const [showValues, setShowValues] = useState(false);
  const serverQuery = "";
  const authorParams = new URLSearchParams();
  if (criterion === "AUTHOR") {
    if (ignoreAnthologies) authorParams.set("ignoreAnthologies", "true");
    if (maxAuthors !== undefined)
      authorParams.set("maxAuthors", String(maxAuthors));
  }
  const selectionParams = detectedSelectionParams(choice).toString();
  const values = useQuery({
    queryKey: [
      "discovery-values",
      source,
      criterion,
      serverQuery,
      authorParams.toString(),
      selectionParams,
    ],
    queryFn: ({ signal }) =>
      get<Page<Value> & { selectedTotal?: number }>(
        `/catalog/discovery/values?source=${source}&criterion=${criterion}&q=${encodeURIComponent(serverQuery)}&size=100&${authorParams}&${selectionParams}`,
        signal,
      ),
    retry: false,
  });
  return (
    <tbody aria-label={t(`discovery.criteria.${criterion}`)}>
      <tr className="discovery-criterion-row">
        <th scope="row" id={rowId}>
          {t(`discovery.criteria.${criterion}`)}
        </th>
        <td
          className="criterion-count"
          aria-label={
            values.data ? number(values.data.meta.totalItems) : undefined
          }
        >
          {values.isError ? (
            "—"
          ) : values.isPending ? (
            "…"
          ) : (
            <Tooltip label={t(`discovery.detectedTitles.${criterion}`)}>
              <button
                type="button"
                className="discovery-count-link"
                aria-describedby={`${rowId}-selection`}
                aria-label={t(
                  criterion === "AUTHOR"
                    ? "discovery.viewAuthors"
                    : "discovery.viewDetectedValues",
                  {
                    criterion: t(`discovery.criteria.${criterion}`),
                    count: values.data?.meta.totalItems ?? 0,
                  },
                )}
                onClick={() => setShowValues(true)}
              >
                {number(values.data?.meta.totalItems ?? 0)}
              </button>
            </Tooltip>
          )}
        </td>
        <td className="criterion-selected" id={`${rowId}-selection`}>
          {values.isError
            ? "—"
            : values.isPending
              ? "…"
              : number(
                  values.data?.selectedTotal ??
                    (choice.all
                      ? Math.max(
                          0,
                          (values.data?.meta.totalItems ?? 0) -
                            (choice.excluded?.length ?? 0),
                        )
                      : choice.values.length),
                )}
        </td>
      </tr>
      {showValues && (
        <tr className="discovery-criterion-details">
          <td colSpan={3}>
            <DetectedValues
              criterion={criterion}
              choice={choice}
              available={values.data?.meta.totalItems ?? 0}
              initialSelected={
                values.data?.selectedTotal ??
                (choice.all
                  ? Math.max(
                      0,
                      (values.data?.meta.totalItems ?? 0) -
                        (choice.excluded?.length ?? 0),
                    )
                  : choice.values.length)
              }
              apply={(next) => {
                onChange(next);
                setShowValues(false);
              }}
              source={source}
              options={authorParams.toString()}
              close={() => {
                setShowValues(false);
                void values.refetch();
              }}
            />
          </td>
        </tr>
      )}
      {values.isError && (
        <tr className="discovery-criterion-details">
          <td colSpan={3}>
            <Failure error={values.error} retry={() => void values.refetch()} />
          </td>
        </tr>
      )}
    </tbody>
  );
}
function AuthorDetectionOptions({
  source,
  ignoreAnthologies,
  maxAuthors,
  onAuthorOptions,
}: {
  source: string;
  ignoreAnthologies: boolean;
  maxAuthors?: number;
  onAuthorOptions: (options: {
    ignoreAnthologies?: boolean;
    maxAuthors?: number;
  }) => void;
}) {
  const { t } = useTranslation();
  const { number } = useLocale();
  const authorParams = new URLSearchParams();
  if (ignoreAnthologies) authorParams.set("ignoreAnthologies", "true");
  if (maxAuthors !== undefined)
    authorParams.set("maxAuthors", String(maxAuthors));
  const summary = useQuery({
    queryKey: [
      "discovery-values",
      source,
      "author-summary",
      authorParams.toString(),
    ],
    queryFn: ({ signal }) =>
      get<{
        totalBooks: number;
        ignoredBooks: number;
        ignoredAnthologies: number;
        ignoredTooMany: number;
        ignoredByBoth: number;
      }>(
        `/catalog/discovery/authors/summary?source=${source}&${authorParams}`,
        signal,
      ),
    retry: false,
  });

  return (
    <section
      className="discovery-author-detection"
      aria-label={t("discovery.authorDetectionTitle")}
    >
      <Text size="sm" fw={600}>
        {t("discovery.authorDetectionTitle")}
      </Text>
      <div className="discovery-author-detection-body">
        <Stack gap="sm">
          <Checkbox
            label={t("discovery.ignoreAnthologies")}
            checked={ignoreAnthologies}
            onChange={(event) =>
              onAuthorOptions({
                ignoreAnthologies: event.currentTarget.checked,
              })
            }
          />
          <Group gap="xs" wrap="wrap">
            <Checkbox
              label={t("discovery.limitAuthors")}
              checked={maxAuthors !== undefined}
              onChange={(event) =>
                onAuthorOptions({
                  maxAuthors: event.currentTarget.checked ? 5 : undefined,
                })
              }
            />
            <NumberInput
              aria-label={t("discovery.maxAuthors")}
              value={maxAuthors ?? 5}
              disabled={maxAuthors === undefined}
              min={1}
              max={2147483647}
              allowDecimal={false}
              allowNegative={false}
              clampBehavior="strict"
              w={90}
              onChange={(value) => {
                if (typeof value === "number" && value > 0)
                  onAuthorOptions({ maxAuthors: value });
              }}
            />
            <Text size="sm">{t("discovery.authorsUnit")}</Text>
          </Group>
        </Stack>
        <>
          {summary.isPending ? (
            <Text size="sm" c="dimmed">
              {t("feedback.loading")}
            </Text>
          ) : summary.isError ? (
            <Failure
              error={summary.error}
              retry={() => void summary.refetch()}
            />
          ) : (
            summary.data && (
              <div className="discovery-ignore-summary">
                <Group gap="md" className="discovery-ignore-summary-line">
                  <Text size="sm" fw={600}>
                    {t("discovery.ignoredBooksTitle")}:{" "}
                    <span>
                      {number(summary.data.ignoredBooks)} /{" "}
                      {number(summary.data.totalBooks)}
                    </span>
                  </Text>
                  <Text size="sm" c="dimmed">
                    <span>{t("discovery.anthologyReason")}</span>:{" "}
                    <Text component="span" inherit fw={600}>
                      {number(
                        ignoreAnthologies ? summary.data.ignoredAnthologies : 0,
                      )}
                    </Text>
                  </Text>
                  <Text size="sm" c="dimmed">
                    <span>
                      {t("discovery.authorLimitReason", {
                        max: maxAuthors ?? 5,
                      })}
                    </span>
                    :{" "}
                    <Text component="span" inherit fw={600}>
                      {number(
                        maxAuthors !== undefined
                          ? summary.data.ignoredTooMany
                          : 0,
                      )}
                    </Text>
                  </Text>
                </Group>

              </div>
            )
          )}
        </>
      </div>
    </section>
  );
}
export function DiscoveryMatches({ book }: { book: Book }) {
  const { t } = useTranslation();
  const { language } = useLocale();
  const groups = new Map<string, Map<string, Set<string>>>();
  for (const match of book.discoveryMatches ?? []) {
    const criterion = match.criterion ?? "";
    const values = groups.get(criterion) ?? new Map<string, Set<string>>();
    const sources = values.get(match.value) ?? new Set<string>();
    sources.add(match.source);
    values.set(match.value, sources);
    groups.set(criterion, values);
  }
  if (!groups.size) return <>—</>;
  return (
    <div className="discovery-matches">
      {[...groups].map(([criterion, values]) => (
        <div key={criterion}>
          {criterion && (
            <Text size="xs" c="dimmed" fw={600}>
              {t(`discovery.criteria.${criterion}`)}
            </Text>
          )}
          <Stack gap={6}>
            {[...values].map(([value, sources]) => (
              <div className="discovery-match-value" key={value}>
                <Text component="span" size="sm">
                  {criterion === "LANGUAGE" ? language(value) : value}
                </Text>
                <Group gap={4}>
                  {[...sources].sort().map((source) => (
                    <Badge
                      key={source}
                      variant="light"
                      color={source === "CALIBRE" ? "grape" : "teal"}
                      tt="none"
                      size="sm"
                      leftSection={
                        source === "CALIBRE" ? (
                          <Library size={11} />
                        ) : (
                          <Download size={11} />
                        )
                      }
                    >
                      {t(`discovery.sources.${source}`)}
                    </Badge>
                  ))}
                </Group>
              </div>
            ))}
          </Stack>
        </div>
      ))}
    </div>
  );
}
