import { useState } from "react";
import {
  ActionIcon,
  Alert,
  Button,
  Checkbox,
  Code,
  Group,
  ScrollArea,
  Select,
  Stack,
  Text,
  TextInput,
} from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Download } from "lucide-react";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { secureFetch } from "../auth/transport";
import { columnLabels, type Column } from "./CatalogColumns";
import { saveCatalogExport } from "./exportTransfer";

type Field = string;
const fields = (Object.keys(columnLabels) as Column[]).filter(
  (column) =>
    column !== "selection" &&
    column !== "rowNumber" &&
    column !== "discoveryMatches",
);
const formats = ["CSV", "JSON"] as const;
const separators = ["COMMA", "SEMICOLON", "TAB", "PIPE"] as const;
type Format = (typeof formats)[number];
type Separator = (typeof separators)[number];
type Preferences = {
  format: Format;
  csv: Separator;
  extensions: Record<Format, string>;
  columns: Field[];
};
const validExtension = /^[a-zA-Z0-9]+(?:[._-][a-zA-Z0-9]+)*$/;
function normalizeExtension(value: string) {
  return value.trim().replace(/^\./, "");
}
function storedExtension(value: unknown, fallback: string) {
  if (typeof value !== "string") return fallback;
  const extension = normalizeExtension(value);
  return extension.length <= 32 && validExtension.test(extension)
    ? extension
    : fallback;
}

export type DataExportConfig = {
  endpoint: string;
  storageKey: string;
  filename: string;
  labels: Record<string, string>;
  defaultColumns: string[];
};
const catalogConfig: DataExportConfig = {
  endpoint: "/api/catalog/data/export",
  storageKey: "eplsync.catalog.dataExport.v1",
  filename: "catalogo",
  labels: columnLabels,
  defaultColumns: ["eplId", "revision", "title", "author"],
};
const defaultPreferences: Preferences = {
  format: "CSV",
  csv: "COMMA",
  extensions: { CSV: "csv", JSON: "json" },
  columns: ["eplId", "revision", "title", "author"],
};
function readPreferences(config: DataExportConfig): Preferences {
  const storageKey = config.storageKey;
  const defaults = { ...defaultPreferences, columns: config.defaultColumns };
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) ?? "null");
    if (!value) return defaults;
    const columns = Array.isArray(value.columns)
      ? [
          ...new Set(
            fieldsFromStorage(value.columns, Object.keys(config.labels)),
          ),
        ]
      : [];
    const legacyText = value.format === "TXT";
    const csv = legacyText ? value.txt : value.csv;
    return {
      format: formats.includes(value.format) ? value.format : defaults.format,
      csv: separators.includes(csv) ? csv : legacyText ? "TAB" : defaults.csv,
      extensions: {
        CSV: storedExtension(value.extensions?.CSV, legacyText ? "txt" : "csv"),
        JSON: storedExtension(value.extensions?.JSON, "json"),
      },
      columns: columns.length ? columns : defaults.columns,
    };
  } catch {
    return defaults;
  }
}

function fieldsFromStorage(values: unknown[], fields: string[]): Field[] {
  return values.filter((value): value is Field =>
    fields.includes(value as Field),
  );
}

type Selection = {
  filters: Record<string, unknown>;
  count: number;
  ordering: string[];
};

export function CatalogDataExportButton(selection: Selection) {
  return (
    <DataExportButton
      {...selection}
      config={{
        ...catalogConfig,
        labels: Object.fromEntries(
          fields.map((field) => [field, columnLabels[field as Column]]),
        ),
      }}
    />
  );
}

export function DataExportButton({
  config,
  ...selection
}: Selection & { config: DataExportConfig }) {
  const { t } = useTranslation();
  const [snapshot, setSnapshot] = useState<Selection>();
  return (
    <>
      <Button
        variant="light"
        leftSection={<Download size={16} />}
        disabled={!selection.count}
        onClick={() => setSnapshot(selection)}
      >
        {t("catalogExport.title")}
      </Button>
      {snapshot && (
        <ExportDialog
          config={config}
          selection={snapshot}
          close={() => setSnapshot(undefined)}
        />
      )}
    </>
  );
}

function ExportDialog({
  config,
  selection,
  close,
}: {
  config: DataExportConfig;
  selection: Selection;
  close: () => void;
}) {
  const { t } = useTranslation();
  const { endpoint, storageKey } = config;
  const fields = Object.keys(config.labels);
  const defaults = { ...defaultPreferences, columns: config.defaultColumns };
  const [settings, setSettings] = useState(() => readPreferences(config));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const request = {
    filters: selection.filters,
    ordering: selection.ordering.join(";") || undefined,
    format: settings.format,
    separator: settings.format === "CSV" ? settings.csv : undefined,
    columns: settings.columns,
  };
  const extensionInput = settings.extensions[settings.format];
  const extension =
    normalizeExtension(extensionInput) || defaults.extensions[settings.format];
  const extensionError =
    extensionInput.trim() !== "" &&
    (!validExtension.test(normalizeExtension(extensionInput)) ||
      extension.length > 32);
  const serialized = JSON.stringify(request);
  const [previewBody] = useDebouncedValue(serialized, 250);
  const preview = useQuery({
    queryKey: ["data-export-preview", endpoint, previewBody],
    enabled: settings.columns.length > 0 && serialized === previewBody,
    retry: false,
    gcTime: 0,
    queryFn: async ({ signal }): Promise<{ text: string; rows: number }> => {
      const response = await secureFetch(endpoint + "/preview", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: previewBody,
        signal,
      });
      const data = await response.json().catch(() => null);
      if (!response.ok || !data)
        throw new Error(data?.details ?? t("catalogExport.error"));
      return data;
    },
  });
  const current = serialized === previewBody && settings.columns.length > 0;
  const update = (next: Preferences) => {
    setError("");
    setSettings(next);
    if (next.columns.length) {
      try {
        localStorage.setItem(storageKey, JSON.stringify(next));
      } catch {
        /* Preferences still work in this dialog. */
      }
    }
  };
  const move = (index: number, delta: number) => {
    const columns = [...settings.columns];
    [columns[index], columns[index + delta]] = [
      columns[index + delta],
      columns[index],
    ];
    update({ ...settings, columns });
  };
  async function download() {
    setBusy(true);
    setError("");
    try {
      await saveCatalogExport(
        endpoint,
        config.filename + "." + extension,
        { ...request, extension },
        t("catalogExport.error"),
        t("catalogExport.empty"),
      );
    } catch (failure) {
      if (!(failure instanceof DOMException && failure.name === "AbortError"))
        setError(
          failure instanceof Error ? failure.message : t("catalogExport.error"),
        );
    } finally {
      setBusy(false);
    }
  }

  const unselected = fields.filter(
    (field) => !settings.columns.includes(field),
  );
  return (
    <AppModal
      opened
      onClose={close}
      title={t("catalogExport.title")}
      icon={Download}
      width="wide"
      closeOnEscape={!busy}
      closeOnClickOutside={!busy}
      withCloseButton={!busy}
    >
      <Stack gap="md">
        <Group grow align="start">
          <Select
            label={t("catalogExport.format")}
            data={[...formats]}
            value={settings.format}
            allowDeselect={false}
            disabled={busy}
            onChange={(value) => {
              if (value) update({ ...settings, format: value as Format });
            }}
          />
          {settings.format !== "JSON" && (
            <Select
              label={t("catalogExport.separator")}
              data={separators.map((value) => ({
                value,
                label: t("catalogExport.separators." + value),
              }))}
              value={request.separator}
              allowDeselect={false}
              disabled={busy}
              onChange={(value) => {
                if (value) update({ ...settings, csv: value as Separator });
              }}
            />
          )}
          <TextInput
            label={t("catalogExport.extension")}
            placeholder={defaults.extensions[settings.format]}
            value={extensionInput}
            maxLength={33}
            disabled={busy}
            error={
              extensionError ? t("catalogExport.invalidExtension") : undefined
            }
            onChange={(event) =>
              update({
                ...settings,
                extensions: {
                  ...settings.extensions,
                  [settings.format]: event.currentTarget.value,
                },
              })
            }
          />
        </Group>
        <div>
          <Text fw={600} mb="xs">
            {t("columns.title")}
          </Text>
          <ScrollArea.Autosize mah={240} type="auto" offsetScrollbars>
            {[...settings.columns, ...unselected].map((field, index) => {
              const selected = index < settings.columns.length;
              const label = t(config.labels[field]);
              return (
                <Group key={field} gap="xs" wrap="nowrap" mb={4}>
                  <Checkbox
                    label={label}
                    checked={selected}
                    disabled={busy}
                    style={{ flex: 1 }}
                    onChange={() =>
                      update({
                        ...settings,
                        columns: selected
                          ? settings.columns.filter((value) => value !== field)
                          : [...settings.columns, field],
                      })
                    }
                  />
                  {selected && (
                    <>
                      <ActionIcon
                        variant="subtle"
                        aria-label={t("columns.up", { column: label })}
                        disabled={busy || index === 0}
                        onClick={() => move(index, -1)}
                      >
                        <ArrowUp size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        aria-label={t("columns.down", { column: label })}
                        disabled={busy || index === settings.columns.length - 1}
                        onClick={() => move(index, 1)}
                      >
                        <ArrowDown size={16} />
                      </ActionIcon>
                    </>
                  )}
                </Group>
              );
            })}
          </ScrollArea.Autosize>
        </div>
        <div aria-live="polite">
          <Text fw={600} mb="xs">
            {t("catalogExport.preview")}
          </Text>
          {!settings.columns.length ? (
            <Text c="dimmed">{t("catalogExport.chooseColumns")}</Text>
          ) : !current || preview.isFetching ? (
            <Text c="dimmed">{t("catalogExport.loading")}</Text>
          ) : preview.isError ? (
            <Alert color="red">
              {preview.error.message}{" "}
              <Button
                variant="subtle"
                size="compact-sm"
                onClick={() => void preview.refetch()}
              >
                {t("feedback.retry")}
              </Button>
            </Alert>
          ) : preview.data?.rows ? (
            <Code
              block
              style={{ maxHeight: 200, overflow: "auto", tabSize: 4 }}
            >
              {preview.data.text}
            </Code>
          ) : (
            <Text c="dimmed">{t("catalogExport.empty")}</Text>
          )}
        </div>
        {error && (
          <Alert color="red" role="alert">
            {error}
          </Alert>
        )}
      </Stack>
      <ModalActions
        summary={
          <Text size="sm" c="dimmed">
            {t("catalogExport.selected", { count: selection.count })}
          </Text>
        }
      >
        <Button variant="default" disabled={busy} onClick={close}>
          {t("catalogExport.close")}
        </Button>
        <Button
          loading={busy}
          disabled={
            extensionError ||
            !current ||
            preview.isFetching ||
            preview.isError ||
            !preview.data?.rows
          }
          leftSection={<Download size={16} />}
          onClick={() => void download()}
        >
          {t("catalogExport.download")}
        </Button>
      </ModalActions>
    </AppModal>
  );
}
