import { AppModal, ModalActions } from "../../components/AppModal";
import { ListChecks } from "lucide-react";
import { useEffect, useState } from "react";
import {
  Checkbox,
  Group,
  Button,
  Alert,
  ScrollArea,
  Stack,
  Text,
  TextInput,
} from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Failure, Loading } from "../../components/Feedback";
import type { Criterion, Choice } from "./Discovery";
import { useLocale } from "../../locales/useLocale";
import type { Page } from "../downloads/shared";

type DetectedValue = {
  value: string;
  label: string;
  counts: Record<string, number>;
};
export function DetectedValues({
  source,
  options,
  criterion,
  close,
  choice,
  apply,
  available,
  initialSelected,
}: {
  source: string;
  options: string;
  criterion: Criterion;
  close: () => void;
  choice: Choice;
  apply: (choice: Choice) => void;
  available: number;
  initialSelected: number;
}) {
  const { t, i18n } = useTranslation();
  const { language } = useLocale();
  const label = (value: DetectedValue) =>
    criterion === "LANGUAGE" ? language(value.label) : value.label;

  const [draft, setDraft] = useState<Choice>(() => ({
    ...choice,
    values: [...choice.values],
    excluded: [...(choice.excluded ?? [])],
  }));
  const [selectedCount, setSelectedCount] = useState(initialSelected);
  const selectedKeys = new Set(
    draft.all ? (draft.excluded ?? []) : draft.values,
  );
  const selected = (value: string) =>
    draft.all ? !selectedKeys.has(value) : selectedKeys.has(value);
  const toggle = (keys: string[], checked: boolean) => {
    const changed = [...new Set(keys)].filter(
      (key) => selected(key) !== checked,
    );
    setSelectedCount((count) =>
      Math.max(0, count + (checked ? changed.length : -changed.length)),
    );
    setDraft((previous) => {
      const values = new Set(
        previous.all ? previous.excluded : previous.values,
      );
      for (const key of changed) {
        if (previous.all ? !checked : checked) values.add(key);
        else values.delete(key);
      }
      return previous.all
        ? { ...previous, excluded: [...values] }
        : { ...previous, values: [...values] };
    });
  };
  const tooLarge =
    (draft.all ? (draft.excluded?.length ?? 0) : draft.values.length) > 10000;
  const sources = source === "BOTH" ? ["DOWNLOADS", "CALIBRE"] : [source];
  const [search, setSearch] = useState("");
  const [query] = useDebouncedValue(search, 300);
  const serverQuery = criterion === "LANGUAGE" ? "" : query;
  const [page, setPage] = useState(1);
  useEffect(() => setPage(1), [query, source, options, criterion]);
  const result = useQuery({
    queryKey: [
      "discovery-values",
      source,
      "detected-values",
      criterion,
      options,
      serverQuery,
      page,
    ],
    queryFn: ({ signal }) =>
      get<Page<DetectedValue> & { selectedTotal?: number }>(
        `/catalog/discovery/values?source=${source}&criterion=${criterion}&q=${encodeURIComponent(serverQuery)}&page=${page - 1}&size=100&${options}`,
        signal,
      ),
    retry: false,
    staleTime: 0,
  });
  const availableTotal = result.data?.selectedTotal ?? available;
  const countLabel = selectedCount.toLocaleString(i18n.resolvedLanguage);
  const normalize = (value: string) =>
    value
      .normalize("NFD")
      .replace(/\p{M}/gu, "")
      .toLocaleLowerCase(i18n.resolvedLanguage);
  const items =
    criterion === "LANGUAGE"
      ? (result.data?.items ?? [])
          .filter(
            (value) =>
              normalize(label(value)).includes(normalize(query)) ||
              normalize(value.value).includes(normalize(query)),
          )
          .sort((a, b) =>
            label(a).localeCompare(label(b), i18n.resolvedLanguage),
          )
      : (result.data?.items ?? []);
  const total =
    criterion === "LANGUAGE"
      ? items.length
      : (result.data?.meta.totalItems ?? 0);
  const pages =
    criterion === "LANGUAGE" ? 1 : (result.data?.meta.totalPages ?? 0);
  useEffect(() => {
    if (result.data && page > Math.max(1, pages)) setPage(Math.max(1, pages));
  }, [result.data, pages, page]);
  return (
    <AppModal
      icon={ListChecks}
      opened
      onClose={close}
      title={t(`discovery.detectedTitles.${criterion}`)}
      width="normal"
    >
      <Stack>
        <Text size="sm" c="dimmed">
          {t("discovery.detectedValuesHelp")}
        </Text>
        <TextInput
          label={t(`discovery.detectedSearch.${criterion}`)}
          value={search}
          onChange={(event) => {
            setSearch(event.currentTarget.value);
            setPage(1);
          }}
        />
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => void result.refetch()} />
        ) : (
          <>
            <Text size="sm">
              {t("discovery.detectedTotal", {
                count: total,
              })}
            </Text>
            <Group gap="xs">
              <Button
                variant="subtle"
                size="compact-sm"
                onClick={() => {
                  setDraft({ all: true, values: [], excluded: [] });
                  setSelectedCount(availableTotal);
                }}
              >
                {t("discovery.selectAllDetected", { count: available })}
              </Button>
              <Button
                variant="subtle"
                size="compact-sm"
                onClick={() => {
                  setDraft({ all: false, values: [] });
                  setSelectedCount(0);
                }}
              >
                {t("discovery.clearDetectedSelection", { count: countLabel })}
              </Button>
            </Group>
            <ScrollArea.Autosize mah={420}>
              {items.length ? (
                <table
                  className="discovery-values-table"
                  aria-label={t(`discovery.detectedTitles.${criterion}`)}
                >
                  <thead>
                    <tr>
                      <th>
                        <Checkbox
                          aria-label={t("discovery.selectDetectedPage")}
                          checked={items.every((value) =>
                            selected(value.value),
                          )}
                          indeterminate={
                            items.some((value) => selected(value.value)) &&
                            !items.every((value) => selected(value.value))
                          }
                          onChange={(event) =>
                            toggle(
                              items.map((value) => value.value),
                              event.currentTarget.checked,
                            )
                          }
                        />
                      </th>
                      <th>{t(`discovery.criteria.${criterion}`)}</th>
                      {sources.map((origin) => (
                        <th key={origin}>{t(`discovery.sources.${origin}`)}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {items.map((value) => (
                      <tr key={value.value}>
                        <td>
                          <Checkbox
                            aria-label={t("discovery.selectDetectedValue", {
                              value: label(value),
                            })}
                            checked={selected(value.value)}
                            onChange={(event) =>
                              toggle([value.value], event.currentTarget.checked)
                            }
                          />
                        </td>
                        <td>{label(value)}</td>
                        {sources.map((origin) => (
                          <td key={origin}>{value.counts[origin] ?? "—"}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <Text>{t("discovery.noDetectedValues")}</Text>
              )}
            </ScrollArea.Autosize>
            {pages > 1 && (
              <nav
                className="pagination cleanup-preview-pagination"
                aria-label={t("discovery.detectedPages")}
              >
                <Button
                  variant="subtle"
                  disabled={page <= 1}
                  onClick={() => setPage((value) => value - 1)}
                >
                  {t("catalog.previous")}
                </Button>
                <span>
                  {page} / {pages}
                </span>
                <Button
                  variant="subtle"
                  disabled={page >= pages}
                  onClick={() => setPage((value) => value + 1)}
                >
                  {t("catalog.next")}
                </Button>
              </nav>
            )}
          </>
        )}
        {tooLarge && <Alert color="red">{t("discovery.selectionLimit")}</Alert>}
        <ModalActions
          summary={
            <Text size="sm" c="dimmed">
              {t("discovery.selectedValues", { count: countLabel })}
            </Text>
          }
        >
          <Button variant="default" onClick={close}>
            {t("discovery.cancelSelection")}
          </Button>
          <Button disabled={tooLarge} onClick={() => apply(draft)}>
            {t("discovery.applySelection")}
          </Button>
        </ModalActions>
      </Stack>
    </AppModal>
  );
}
