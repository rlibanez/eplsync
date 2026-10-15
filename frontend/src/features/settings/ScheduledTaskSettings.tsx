import cronstrue from "cronstrue";
import "cronstrue/locales/es";
import { useDebouncedValue } from "@mantine/hooks";
import { OptionLabel } from "../downloads/SendOptions";
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Group,
  Stack,
  Switch,
  Select,
  TextInput,
  Alert,
  Tooltip,
} from "@mantine/core";
import { CalendarClock, Pencil, Plus, Play } from "lucide-react";
import { useTranslation } from "react-i18next";
import { get, ApiError, NetworkError } from "../../api/catalog";
import { secureFetch } from "../auth/transport";
import { Loading, Failure } from "../../components/Feedback";
import { AppModal, ModalActions } from "../../components/AppModal";
import { useLocale } from "../../locales/useLocale";
import { useSettingsSaved } from "./useSettingsSaved";

type Operation =
  | "TORRENT_SYNC"
  | "CALIBRE_SYNC"
  | "CATALOG_IMPORT"
  | "TORRENT_UPDATE_SEARCH"
  | "CALIBRE_UPDATE_SEARCH";
interface Input {
  name: string;
  operation: Operation | null;
  cron: string;
  enabled: boolean;
}
interface Task extends Omit<Input, "operation"> {
  operation: Operation;
  timezone: string;
  id: string;
  ownerId: string | null;
  ownerUsername: string;
  running: boolean;
  nextRun: string | null;
  lastRun: string | null;
  lastFinished: string | null;
  lastResult: string | null;
  lastMessage: string | null;
}
interface Period {
  id: string;
  interval: string;
}
interface View {
  tasks: Task[];
  internal: Period[];
  defaultTimezone: string;
}
async function request<T>(
  path: string,
  method: string,
  body: unknown,
): Promise<T> {
  let response: Response;
  try {
    response = await secureFetch(`/api/settings/tasks${path}`, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    throw new NetworkError();
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, null, data?.details);
  return data;
}
export function ScheduledTaskSettings() {
  const { t } = useTranslation();
  const { date, number } = useLocale();
  const cache = useQueryClient();
  const saved = useSettingsSaved();
  const query = useQuery({
    queryKey: ["scheduled-tasks"],
    queryFn: ({ signal }) => get<View>("/settings/tasks", signal),
    refetchInterval: 5000,
  });
  const [editing, setEditing] = useState<{ id?: string; input: Input } | null>(
    null,
  );
  const toggle = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      request<Task>(`/${id}/enabled`, "POST", { enabled }),
    onSuccess: () => {
      saved();
      void cache.invalidateQueries({ queryKey: ["scheduled-tasks"] });
    },
  });
  const run = useMutation({
    mutationFn: (id: string) => request<Task>(`/${id}/run`, "POST", {}),
    onSuccess: () => {
      void cache.invalidateQueries({ queryKey: ["scheduled-tasks"] });
    },
  });
  if (query.isPending) return <Loading />;
  if (query.isError)
    return (
      <Failure
        error={query.error}
        retry={() => {
          void query.refetch();
        }}
      />
    );
  const open = (task?: Task) => {
    toggle.reset();
    setEditing(
      task
        ? {
            id: task.id,
            input: {
              name: task.name,
              operation: task.operation,
              cron: task.cron,
              enabled: task.enabled,
            },
          }
        : {
            input: {
              name: "",
              operation: null,
              cron: "0 5 * * *",
              enabled: true,
            },
          },
    );
  };
  return (
    <>
      <section className="panel settings-section">
        <h2>{t("scheduledTasks.internalTitle")}</h2>
        <p>{t("scheduledTasks.internalHelp")}</p>
        <Stack gap="lg">
          {query.data.internal.map((period) => (
            <PeriodEditor key={period.id} period={period} />
          ))}
        </Stack>
      </section>
      <section className="panel settings-section">
        <Group justify="space-between" mb="md">
          <h2 style={{ margin: 0 }}>{t("scheduledTasks.title")}</h2>
          <Button leftSection={<Plus size={16} />} onClick={() => open()}>
            {t("scheduledTasks.create")}
          </Button>
        </Group>
        <p>{t("scheduledTasks.description")}</p>
        {(toggle.isError || run.isError) && (
          <Alert color="red" role="alert">
            {((run.error ?? toggle.error) as ApiError)?.details ??
              (run.error ?? toggle.error)?.message}
          </Alert>
        )}
        <div className="table-scroll">
          <table
            className="catalog-table"
            style={{ minWidth: 1700, width: "100%" }}
          >
            <colgroup>
              {[180, 170, 120, 180, 150, 180, 180, 100, 150, 80, 250].map(
                (width, index) => (
                  <col key={index} style={{ width }} />
                ),
              )}
            </colgroup>
            <thead>
              <tr>
                {[
                  "name",
                  "operation",
                  "owner",
                  "cron",
                  "timezone",
                  "next",
                  "last",
                  "duration",
                  "result",
                  "enabled",
                  "actions",
                ].map((key) => (
                  <th key={key}>{t(`scheduledTasks.${key}`)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {query.data.tasks.length === 0 ? (
                <tr>
                  <td colSpan={11}>{t("scheduledTasks.empty")}</td>
                </tr>
              ) : (
                query.data.tasks.map((task) => (
                  <tr key={task.id}>
                    <td>{task.name}</td>
                    <td>{t(`scheduledTasks.operations.${task.operation}`)}</td>
                    <td>{task.ownerUsername}</td>
                    <td>
                      <code style={{ whiteSpace: "nowrap" }}>{task.cron}</code>
                    </td>
                    <td style={{ whiteSpace: "nowrap" }}>{task.timezone}</td>
                    <td>{date(task.nextRun)}</td>
                    <td>{date(task.lastRun)}</td>
                    <td style={{ whiteSpace: "nowrap" }}>
                      {task.lastRun && task.lastFinished && !task.running
                        ? `${number(Math.max(0, (Date.parse(task.lastFinished) - Date.parse(task.lastRun)) / 1000), { maximumFractionDigits: 1 })} s`
                        : "—"}
                    </td>
                    <td>
                      <span className="badge">
                        {task.running
                          ? t("scheduledTasks.results.RUNNING")
                          : task.lastResult
                            ? t(`scheduledTasks.results.${task.lastResult}`)
                            : "—"}
                      </span>
                      {task.lastMessage && (
                        <small className="hash-text">{task.lastMessage}</small>
                      )}
                    </td>
                    <td>
                      <Switch
                        aria-label={t("scheduledTasks.toggle", {
                          name: task.name,
                        })}
                        checked={task.enabled}
                        disabled={toggle.isPending}
                        onChange={(e) =>
                          toggle.mutate({
                            id: task.id,
                            enabled: e.currentTarget.checked,
                          })
                        }
                      />
                    </td>
                    <td>
                      <Button
                        size="xs"
                        variant="subtle"
                        leftSection={<Play size={14} />}
                        disabled={task.running || run.isPending}
                        onClick={() => run.mutate(task.id)}
                      >
                        {t("scheduledTasks.runNow")}
                      </Button>
                      <Button
                        size="xs"
                        variant="subtle"
                        leftSection={<Pencil size={14} />}
                        disabled={task.running}
                        onClick={() => open(task)}
                      >
                        {t("scheduledTasks.edit")}
                      </Button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </section>
      {editing && (
        <TaskEditor
          initial={editing.input}
          id={editing.id}
          onClose={() => setEditing(null)}
        />
      )}
    </>
  );
}
function PeriodEditor({ period }: { period: Period }) {
  const { t } = useTranslation();
  const [value, setValue] = useState(period.interval);
  const cache = useQueryClient();
  const saved = useSettingsSaved();
  useEffect(() => setValue(period.interval), [period.interval]);
  const mutation = useMutation({
    mutationFn: (restore: boolean) =>
      restore
        ? request<Period>(`/internal/${period.id}/restore`, "POST", {})
        : request<Period>(`/internal/${period.id}`, "PUT", { interval: value }),
    onSuccess: (result) => {
      setValue(result.interval);
      saved();
      void cache.invalidateQueries({ queryKey: ["scheduled-tasks"] });
    },
  });
  return (
    <div>
      <Group align="end">
        <TextInput
          w={300}
          maw="100%"
          label={
            <OptionLabel
              text={t(`scheduledTasks.internal.${period.id}`)}
              help={t("scheduledTasks.intervalHelp")}
            />
          }
          value={value}
          onChange={(e) => {
            setValue(e.currentTarget.value);
            mutation.reset();
          }}
        />
        <Button
          disabled={value === period.interval || !value || mutation.isPending}
          loading={mutation.isPending}
          onClick={() => mutation.mutate(false)}
        >
          {t("auth.save")}
        </Button>
        <Button
          variant="subtle"
          disabled={mutation.isPending}
          onClick={() => mutation.mutate(true)}
          title={t("scheduledTasks.restoreHelp")}
        >
          {t("scheduledTasks.restore")}
        </Button>
      </Group>
      {mutation.isError && (
        <Alert mt="sm" color="red" role="alert">
          {mutation.error instanceof ApiError && mutation.error.details
            ? mutation.error.details
            : mutation.error.message}
        </Alert>
      )}
    </div>
  );
}
function TaskEditor({
  id,
  initial,
  onClose,
}: {
  id?: string;
  initial: Input;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const [value, setValue] = useState(initial);
  const [cron] = useDebouncedValue(value.cron.trim(), 350);
  const preview = useQuery({
    queryKey: ["scheduled-cron-preview", cron],
    queryFn: ({ signal }) =>
      get<{ expression: string; timezone: string; executions: string[] }>(
        `/settings/tasks/cron?cron=${encodeURIComponent(cron)}`,
        signal,
      ),
    enabled: !!cron,
    retry: false,
    staleTime: 0,
  });
  const current = !!cron && cron === value.cron.trim();
  let description = "";
  if (current && preview.data) {
    try {
      description = cronstrue.toString(preview.data.expression, {
        locale: i18n.resolvedLanguage?.startsWith("es") ? "es" : "en",
        use24HourTimeFormat: true,
        verbose: true,
      });
    } catch {
      description = t("scheduledTasks.validCron");
    }
  }
  const formatExecution = (date: string) =>
    new Intl.DateTimeFormat(i18n.resolvedLanguage ?? "en", {
      timeZone: preview.data!.timezone,
      dateStyle: "medium",
      timeStyle: "medium",
    }).format(new Date(date));
  const cache = useQueryClient();
  const saved = useSettingsSaved();
  const mutation = useMutation({
    mutationFn: () =>
      request<Task>(id ? `/${id}` : "", id ? "PUT" : "POST", value),
    onSuccess: () => {
      saved();
      void cache.invalidateQueries({ queryKey: ["scheduled-tasks"] });
      onClose();
    },
  });
  const change = <K extends keyof Input>(key: K, next: Input[K]) => {
    mutation.reset();
    setValue((previous) => ({ ...previous, [key]: next }));
  };
  return (
    <AppModal
      opened
      onClose={() => {
        if (!mutation.isPending) onClose();
      }}
      icon={CalendarClock}
      title={t(id ? "scheduledTasks.edit" : "scheduledTasks.create")}
    >
      <TextInput
        label={t("scheduledTasks.name")}
        required
        maxLength={120}
        value={value.name}
        disabled={mutation.isPending}
        onChange={(e) => change("name", e.currentTarget.value)}
      />
      <Select
        label={t("scheduledTasks.operation")}
        required
        allowDeselect={false}
        placeholder={t("scheduledTasks.chooseOperation")}
        value={value.operation}
        data={(
          [
            "TORRENT_SYNC",
            "CALIBRE_SYNC",
            "CATALOG_IMPORT",
            "TORRENT_UPDATE_SEARCH",
            "CALIBRE_UPDATE_SEARCH",
          ] as Operation[]
        )
          .map((operation) => ({
            value: operation,
            label: t(`scheduledTasks.operations.${operation}`),
          }))
          .sort((a, b) =>
            a.label.localeCompare(b.label, i18n.resolvedLanguage ?? "en", {
              sensitivity: "base",
            }),
          )}
        onChange={(operation) =>
          change("operation", operation as Operation | null)
        }
        disabled={mutation.isPending}
      />
      <TextInput
        label={t("scheduledTasks.cron")}
        rightSectionWidth={68}
        rightSectionPointerEvents="all"
        rightSection={
          <Tooltip
            disabled={
              !current || !preview.data || preview.isError || preview.isFetching
            }
            multiline
            w={360}
            maw="calc(100vw - 32px)"
            withArrow
            events={{ hover: true, focus: true, touch: true }}
            label={
              current && preview.data && !preview.isError ? (
                <div>
                  <p style={{ margin: "0 0 8px" }}>{description}</p>
                  <strong>
                    {t("scheduledTasks.nextExecutions", {
                      timezone: preview.data.timezone,
                    })}
                  </strong>
                  <ol style={{ margin: "8px 0 0", paddingLeft: 20 }}>
                    {preview.data.executions.map((date) => (
                      <li key={date}>{formatExecution(date)}</li>
                    ))}
                  </ol>
                </div>
              ) : (
                ""
              )
            }
          >
            <Button
              type="button"
              size="compact-xs"
              variant="subtle"
              aria-label={t("scheduledTasks.cronInfo")}
              loading={!!value.cron.trim() && (!current || preview.isFetching)}
              disabled={!value.cron.trim() || (current && preview.isError)}
            >
              {t("scheduledTasks.info")}
            </Button>
          </Tooltip>
        }
        required
        value={value.cron}
        maxLength={256}
        onChange={(e) => change("cron", e.currentTarget.value)}
        disabled={mutation.isPending}
        error={
          current && preview.isError
            ? preview.error instanceof ApiError && preview.error.details
              ? preview.error.details
              : preview.error.message
            : undefined
        }
        description={
          <>
            {t("scheduledTasks.cronHelp")}{" "}
            <a
              style={{
                color: "var(--mantine-primary-color-filled)",
                textDecoration: "underline",
              }}
              href="https://crontab.guru/"
              target="_blank"
              rel="noopener noreferrer"
            >
              crontab.guru
            </a>
            {" · "}
            <a
              style={{
                color: "var(--mantine-primary-color-filled)",
                textDecoration: "underline",
              }}
              href="https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-cron-expression"
              target="_blank"
              rel="noopener noreferrer"
            >
              {t("scheduledTasks.secondsFormat")}
            </a>
          </>
        }
      />
      <Switch
        label={t("scheduledTasks.enabled")}
        checked={value.enabled}
        onChange={(e) => change("enabled", e.currentTarget.checked)}
        disabled={mutation.isPending}
      />
      {mutation.isError && (
        <Alert color="red" role="alert">
          {mutation.error instanceof ApiError && mutation.error.details
            ? mutation.error.details
            : mutation.error.message}
        </Alert>
      )}
      <ModalActions>
        <Button
          variant="default"
          disabled={mutation.isPending}
          onClick={onClose}
        >
          {t("import.cancel")}
        </Button>
        <Button
          loading={mutation.isPending}
          disabled={
            !value.name.trim() ||
            !value.operation ||
            !current ||
            !preview.data ||
            preview.isError ||
            preview.isFetching
          }
          onClick={() => mutation.mutate()}
        >
          {t("auth.save")}
        </Button>
      </ModalActions>
    </AppModal>
  );
}
