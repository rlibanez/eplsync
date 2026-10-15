import {
  Checkbox,
  Group,
  MultiSelect,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  TextInput,
  TagsInput,
  Alert,
  Text,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
export interface AutomationConfiguration {
  updateCatalog: boolean;
  limit: number;
  states: string[];
  options: {
    hash: null;
    start: boolean;
    savePath: string;
    rename: { enabled: boolean; pattern: string };
    qbittorrent: { category: string; tags: string[]; autoManagement: boolean };
  };
  concurrency: number;
  batchSize: number;
  interval: string;
  hashes: string;
  torrentCleanup: string;
  calibreCleanup: string;
  maxRetries: number;
  library: string | null;
  torrentTarget: string | null;
  calibreTarget: string | null;
}
export function AutomationFields({
  value,
  onChange,
  step,
  calibre,
  disabled,
}: {
  value: AutomationConfiguration;
  onChange: (value: AutomationConfiguration) => void;
  step: number;
  calibre: boolean;
  disabled: boolean;
}) {
  const { t } = useTranslation();
  const set = <K extends keyof AutomationConfiguration>(
    key: K,
    v: AutomationConfiguration[K],
  ) => onChange({ ...value, [key]: v });
  const download = (patch: Partial<AutomationConfiguration["options"]>) =>
    set("options", { ...value.options, ...patch });
  const qb = (
    patch: Partial<AutomationConfiguration["options"]["qbittorrent"]>,
  ) => download({ qbittorrent: { ...value.options.qbittorrent, ...patch } });
  const policies = [
    { value: "keep", label: t("automation.keep") },
    { value: "removeTorrent", label: t("automation.removeTorrent") },
    { value: "removeTorrentAndFiles", label: t("automation.removeFiles") },
  ];
  const calibrePolicies = [
    { value: "KEEP", label: t("automation.keep") },
    { value: "EPUB", label: t("automation.removeEpub") },
    { value: "BOOK", label: t("automation.removeBook") },
  ];
  return (
    <Stack gap="md">
      {step === 0 && (
        <>
          <Checkbox
            label={t("automation.updateCatalog")}
            checked={value.updateCatalog}
            onChange={(e) => set("updateCatalog", e.currentTarget.checked)}
            disabled={disabled}
          />
          <Text size="sm" c="dimmed">
            {t(calibre ? "automation.calibreScope" : "automation.torrentScope")}
          </Text>
          {!calibre && (
            <MultiSelect
              label={t("automation.states")}
              data={[
                "SUBMITTED",
                "ALREADY_EXISTS",
                "UNKNOWN",
                "QUEUED",
                "DOWNLOADING",
                "PAUSED",
                "CHECKING",
                "DOWNLOADED",
                "ERROR",
                "NOT_FOUND",
              ].map((v) => ({ value: v, label: t(`statuses.${v}`) }))}
              value={value.states}
              onChange={(v) => set("states", v)}
              disabled={disabled}
            />
          )}
          <NumberInput
            label={t("automation.limit")}
            min={1}
            max={1000}
            value={value.limit}
            onChange={(v) => set("limit", Number(v))}
            disabled={disabled}
          />
        </>
      )}
      {step === 1 && (
        <>
          <Text size="sm" c="dimmed">
            {t("automation.reuse")}
          </Text>
          <Group>
            <Checkbox
              label={t("automation.start")}
              checked={value.options.start}
              onChange={(e) => download({ start: e.currentTarget.checked })}
              disabled={disabled}
            />
            <Checkbox
              label={t("automation.autoManagement")}
              checked={value.options.qbittorrent.autoManagement}
              onChange={(e) => qb({ autoManagement: e.currentTarget.checked })}
              disabled={disabled}
            />
          </Group>
          <TextInput
            label={t("automation.savePath")}
            value={value.options.savePath}
            maxLength={4096}
            onChange={(e) => download({ savePath: e.currentTarget.value })}
            disabled={disabled || value.options.qbittorrent.autoManagement}
          />
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <TextInput
              label={t("automation.category")}
              value={value.options.qbittorrent.category}
              onChange={(e) => qb({ category: e.currentTarget.value })}
              disabled={disabled}
            />
            <TagsInput
              label={t("automation.tags")}
              value={value.options.qbittorrent.tags}
              onChange={(v) => qb({ tags: v })}
              disabled={disabled}
              splitChars={[","]}
            />
          </SimpleGrid>
          <Checkbox
            label={t("automation.rename")}
            checked={value.options.rename.enabled}
            onChange={(e) =>
              download({
                rename: {
                  ...value.options.rename,
                  enabled: e.currentTarget.checked,
                },
              })
            }
            disabled={disabled}
          />
          {value.options.rename.enabled && (
            <TextInput
              label={t("automation.pattern")}
              value={value.options.rename.pattern}
              onChange={(e) =>
                download({
                  rename: {
                    ...value.options.rename,
                    pattern: e.currentTarget.value,
                  },
                })
              }
              disabled={disabled}
            />
          )}
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            <NumberInput
              label={t("automation.concurrency")}
              min={1}
              max={16}
              value={value.concurrency}
              onChange={(v) => set("concurrency", Number(v))}
              disabled={disabled}
            />
            <NumberInput
              label={t("automation.batchSize")}
              min={1}
              max={1000}
              value={value.batchSize}
              onChange={(v) => set("batchSize", Number(v))}
              disabled={disabled}
            />
            <TextInput
              label={t("automation.interval")}
              value={value.interval}
              onChange={(e) => set("interval", e.currentTarget.value)}
              disabled={disabled}
            />
          </SimpleGrid>
        </>
      )}
      {step === 2 && (
        <>
          <Select
            label={t("automation.torrentCleanup")}
            data={policies}
            value={value.torrentCleanup}
            onChange={(v) => set("torrentCleanup", v ?? "keep")}
            allowDeselect={false}
            disabled={disabled}
          />
          {calibre && (
            <Select
              label={t("automation.calibreCleanup")}
              data={calibrePolicies}
              value={value.calibreCleanup}
              onChange={(v) => set("calibreCleanup", v ?? "KEEP")}
              allowDeselect={false}
              disabled={disabled}
            />
          )}
          <Text size="sm" c="dimmed">
            {t("automation.cleanupHelp")}
          </Text>
          <NumberInput
            label={t("automation.retries")}
            min={0}
            max={3}
            value={value.maxRetries}
            onChange={(v) => set("maxRetries", Number(v))}
            disabled={disabled}
          />
          <Text size="sm" c="dimmed">
            {t("automation.retryHelp")}
          </Text>
        </>
      )}
      {step === 3 && (
        <Alert title={t("automation.summary")}>
          <Stack gap="xs">
            <Text size="sm">
              {t(
                calibre ? "automation.calibreScope" : "automation.torrentScope",
              )}
            </Text>
            <Text size="sm">
              {t("automation.summaryLimit", { count: value.limit })}
            </Text>
            <Text size="sm">
              {t(
                value.updateCatalog
                  ? "automation.withCatalog"
                  : "automation.withoutCatalog",
              )}
            </Text>
            <Text size="sm">
              {t("automation.torrentCleanup")}:{" "}
              {policies.find((p) => p.value === value.torrentCleanup)?.label}
            </Text>
            {calibre && (
              <Text size="sm">
                {t("automation.calibreCleanup")}:{" "}
                {
                  calibrePolicies.find((p) => p.value === value.calibreCleanup)
                    ?.label
                }
              </Text>
            )}
            <Text size="sm">{t("automation.frozen")}</Text>
          </Stack>
        </Alert>
      )}
    </Stack>
  );
}
