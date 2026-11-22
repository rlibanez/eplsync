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
  MultiSelect,
  Select,
  Stack,
  Text,
} from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
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
type Criterion = (typeof criteria)[number];
type Choice = { all: boolean; values: string[] };
export const discoveryKeys = [
  "discoverySource",
  "discoveryCriterion",
  "discoveryCriteria",
  "discoveryMatch",
  "discoveryValues",
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
      ].slice(0, 1000)
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
    selectedCriteria.some(
      (criterion) =>
        !choiceFor(config, criterion).all &&
        !choiceFor(config, criterion).values.length,
    )
  )
    return false;
  discoveryKeys.forEach((key) => next.delete(key));
  next.set("discoverySource", config.source);
  selectedCriteria.forEach((criterion) =>
    next.append("discoveryCriteria", criterion),
  );
  next.set("discoveryMatch", config.match);
  next.set("discoveryExcludePending", String(config.pending));
  for (const criterion of selectedCriteria) {
    const choice = choiceFor(config, criterion);
    if (!choice.all)
      choice.values.forEach((value) =>
        next.append("discoveryValues", `${criterion}:${value}`),
      );
  }
  return true;
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
      downloads: presence.torrent === "ABSENT",
      calibre: presence.calibre === "ABSENT",
      pending: fromUrl
        ? params.get("discoveryExcludePending") === "true"
        : saved.pending !== false,
    };
  });
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
  const invalid =
    !preferences.criteria.length ||
    preferences.criteria.some(
      (criterion) =>
        !choiceFor(preferences, criterion).all &&
        !choiceFor(preferences, criterion).values.length,
    );
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
                  <col className="all-column" />
                  <col className="count-column" />
                </colgroup>
                <thead>
                  <tr>
                    <th scope="col">{t("discovery.criterionColumn")}</th>
                    <th scope="col">
                      <span className="criteria-all-wide">
                        {t("discovery.useAll")}
                      </span>
                      <span className="criteria-all-narrow">
                        {t("discovery.modes.ALL")}
                      </span>
                    </th>
                    <th scope="col">{t("discovery.availableHeader")}</th>
                  </tr>
                </thead>
                {preferences.criteria.map((criterion) => (
                  <CriterionValues
                    key={`${preferences.source}:${criterion}`}
                    source={preferences.source}
                    criterion={criterion}
                    choice={choiceFor(preferences, criterion)}
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
}: {
  source: string;
  criterion: Criterion;
  choice: Choice;
  onChange: (choice: Choice) => void;
}) {
  const { t } = useTranslation();
  const { language, number } = useLocale();
  const rowId = useId();
  const [query, setQuery] = useState("");
  const [debounced] = useDebouncedValue(query, 300);
  const [labels, setLabels] = useState<Record<string, string>>({});
  const serverQuery = criterion === "LANGUAGE" ? "" : debounced;
  const values = useQuery({
    queryKey: ["discovery-values", source, criterion, serverQuery],
    queryFn: ({ signal }) =>
      get<Page<Value>>(
        `/catalog/discovery/values?source=${source}&criterion=${criterion}&q=${encodeURIComponent(serverQuery)}&size=100`,
        signal,
      ),
    retry: false,
  });
  const label = (value: string) =>
    criterion === "LANGUAGE" ? language(value) : value;
  useEffect(() => {
    if (values.data)
      setLabels((previous) => ({
        ...previous,
        ...Object.fromEntries(
          values.data.items.map((item) => [item.value, item.label]),
        ),
      }));
  }, [values.data]);
  const data = [
    ...(values.data?.items ?? []).map((item) => ({
      value: item.value,
      label: `${label(item.label)} · ${Object.entries(item.counts)
        .map(
          ([origin, count]) => `${t(`discovery.sources.${origin}`)}: ${count}`,
        )
        .join(" · ")}`,
    })),
    ...choice.values
      .filter(
        (value) => !values.data?.items.some((item) => item.value === value),
      )
      .map((value) => ({ value, label: label(labels[value] ?? value) })),
  ];
  return (
    <tbody aria-label={t(`discovery.criteria.${criterion}`)}>
      <tr className="discovery-criterion-row">
        <th scope="row" id={rowId}>
          {t(`discovery.criteria.${criterion}`)}
        </th>
        <td className="criterion-all">
          <Checkbox
            aria-label={t("discovery.all")}
            aria-describedby={rowId}
            checked={choice.all}
            onChange={(event) =>
              onChange({ ...choice, all: event.currentTarget.checked })
            }
          />
        </td>
        <td className="criterion-count">
          {values.isError
            ? "—"
            : values.isPending
              ? "…"
              : number(values.data?.meta.totalItems ?? 0)}
        </td>
      </tr>
      {(!choice.all || values.isError) && (
        <tr className="discovery-criterion-details">
          <td colSpan={3}>
            <Stack gap="xs">
              {!choice.all && (
                <MultiSelect
                  aria-label={t(`discovery.criteria.${criterion}`)}
                  aria-describedby={rowId}
                  description={t("discovery.valuesHelp")}
                  searchable
                  clearable
                  maxValues={1000}
                  value={choice.values}
                  data={data}
                  searchValue={query}
                  onSearchChange={setQuery}
                  onFocus={() => void values.refetch()}
                  onChange={(selected) =>
                    onChange({ ...choice, values: selected })
                  }
                />
              )}
              {values.isError && (
                <Failure error={values.error} retry={() => values.refetch()} />
              )}
            </Stack>
          </td>
        </tr>
      )}
    </tbody>
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
