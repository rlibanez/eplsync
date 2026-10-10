import { useAuth } from "../auth/Auth";
import { secureFetch } from "../auth/transport";
import { SettingsList } from "./SettingsList";
import { OptionLabel } from "../downloads/SendOptions";
import {
  CoverParameter,
  durationMs,
  validCoverOptions,
} from "./CoverParameter";
import { RotateCcw } from "lucide-react";
import { useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Checkbox,
  PasswordInput,
  Select,
  TextInput,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import { get, ApiError } from "../../api/catalog";
import { Loading, Failure } from "../../components/Feedback";
import { AppModal, ModalActions } from "../../components/AppModal";
import { SecretKeyDialog } from "./SecretKeyDialog";
import { useNotifications } from "../notifications/Notifications";

class SecretKeyRequiredError extends ApiError {}

type SettingsActions =
  ReactNode | ((values: Record<string, Field["value"]>) => ReactNode);

type Field = {
  key: string;
  type: string;
  value: string | number | boolean | string[];
  overridden: boolean;
  configured: boolean;
};
type View = {
  section: string;
  fields: Field[];
  credentialsError?: string | null;
};
export function ServerSettings({
  section,
  actions,
  children,
}: {
  section: "catalog" | "covers" | "torrent" | "events";
  actions?: SettingsActions;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const result = useQuery({
    queryKey: ["server-settings", section],
    queryFn: ({ signal }) => get<View>(`/settings/${section}`, signal),
    refetchOnWindowFocus: false,
  });
  return (
    <section className="panel settings-section">
      <h2>{t(`serverSettings.sections.${section}`)}</h2>
      <p>{t("serverSettings.description")}</p>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => result.refetch()} />
      ) : (
        <SettingsForm
          key={result.dataUpdatedAt}
          view={result.data}
          actions={actions}
        />
      )}
      {children}
    </section>
  );
}
function SettingsForm({
  view,
  actions,
}: {
  view: View;
  actions?: SettingsActions;
}) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const { user } = useAuth();
  const connectionAdmin = user?.role === "ADMIN";
  const { notify } = useNotifications();
  const [values, setValues] = useState<Record<string, Field["value"]>>({});
  const [confirm, setConfirm] = useState(false);
  const [secretKeyDialog, setSecretKeyDialog] = useState(false);
  const normalizeDestination = (value: Field["value"] | undefined) => {
    try {
      const url = new URL(String(value));
      return url.origin + url.pathname.replace(/\/+$/, "");
    } catch {
      return String(value);
    }
  };
  const originalDestination = view.fields.find(
    (f) => f.key === "torrent.base-url",
  )?.value;
  const destinationChanged =
    view.section === "torrent" &&
    normalizeDestination(values["torrent.base-url"] ?? originalDestination) !==
      normalizeDestination(originalDestination);
  const change = (key: string, value: Field["value"]) =>
    setValues((old) => {
      const next = { ...old, [key]: value };
      if (
        key === "torrent.base-url" &&
        normalizeDestination(value) !==
          normalizeDestination(old[key] ?? originalDestination)
      ) {
        next["torrent.qbittorrent.auth.username"] = "";
        delete next["torrent.qbittorrent.auth.password"];
        delete next["torrent.qbittorrent.auth.api-key"];
      }
      return next;
    });
  const mutation = useMutation({
    meta: { silentSuccess: true, notice: { title: "serverSettings.title" } },
    mutationFn: async (restore: boolean) => {
      const response = await secureFetch(`/api/settings/${view.section}`, {
        method: restore ? "DELETE" : "PUT",
        headers: { "Content-Type": "application/json" },
        body: restore ? undefined : JSON.stringify(values),
      });
      if (!response.ok) {
        const error = await response.json().catch(() => null);
        if (error?.code === "SECRET_KEY_REQUIRED")
          throw new SecretKeyRequiredError(
            response.status,
            undefined,
            t("serverSettings.secretKey.help"),
          );
        throw new ApiError(
          response.status,
          undefined,
          error?.details ?? error?.message,
        );
      }
      return response.json() as Promise<View>;
    },
    onError: (error) => {
      if (error instanceof SecretKeyRequiredError) setSecretKeyDialog(true);
    },
    onSuccess: (data, restore) => {
      setConfirm(false);
      cache.setQueryData(["server-settings", view.section], data);
      for (const queryKey of [
        ["send-defaults"],
        ["torrent-categories"],
        ["cover-config"],
        ["event-retention"],
        ["import-source"],
      ])
        void cache.invalidateQueries({ queryKey });
      notify({
        title: t("serverSettings.title"),
        message: t(
          restore ? "serverSettings.restored" : "serverSettings.saved",
        ),
        tone: "success",
      });
    },
  });
  const groupOf = (f: Field) =>
    view.section !== "torrent"
      ? ""
      : f.key.includes(".auth.")
        ? "auth"
        : f.key.includes(".bulk.")
          ? "execution"
          : f.key.includes(".download.") || f.key.includes(".rename.")
            ? "defaults"
            : "connection";
  const groups = [...new Set(view.fields.map(groupOf))];
  const currentValue = (key: string) =>
    values[key] ?? view.fields.find((f) => f.key === key)?.value;
  const valid =
    view.section !== "covers" ||
    validCoverOptions({
      connectTimeoutMs: durationMs(
        String(currentValue("catalog.cover-check.connect-timeout")),
      ),
      requestTimeoutMs: durationMs(
        String(currentValue("catalog.cover-check.request-timeout")),
      ),
      batchTimeoutMs: durationMs(
        String(currentValue("catalog.cover-check.batch-timeout")),
      ),
      concurrency: Number(currentValue("catalog.cover-check.concurrency")),
    });
  const dependentGroups = [
    {
      name: "location",
      keys: [
        "torrent.qbittorrent.download.auto-management",
        "torrent.download.save-path",
      ],
    },
    {
      name: "naming",
      keys: ["torrent.rename.enabled", "torrent.rename.pattern"],
    },
  ];
  function field(f: Field) {
    const value = values[f.key] ?? f.value;
    const label = t(`serverSettings.fields.${f.key.replaceAll(".", "_")}`);

    const props = {
      label:
        view.section === "catalog" || view.section === "torrent" ? (
          <OptionLabel
            text={label}
            help={t(`serverSettings.help.${f.key.replaceAll(".", "_")}`)}
          />
        ) : (
          label
        ),
      disabled:
        mutation.isPending ||
        (!connectionAdmin &&
          (f.key === "torrent.base-url" || f.key.includes(".auth."))) ||
        (f.key === "torrent.download.save-path" &&
          currentValue("torrent.qbittorrent.download.auto-management") ===
            true) ||
        (f.key === "torrent.rename.pattern" &&
          currentValue("torrent.rename.enabled") === false),
      classNames: {
        input: Object.hasOwn(values, f.key)
          ? "send-option-modified"
          : undefined,
      },
    };
    if (view.section === "covers") {
      const names = {
        "connect-timeout": "connectTimeoutMs",
        "request-timeout": "requestTimeoutMs",
        "batch-timeout": "batchTimeoutMs",
        concurrency: "concurrency",
      } as const;
      const name = names[f.key.split(".").at(-1) as keyof typeof names];
      return (
        <CoverParameter
          name={name}
          value={
            name === "concurrency" ? Number(value) : durationMs(String(value))
          }
          disabled={mutation.isPending}
          modified={Object.hasOwn(values, f.key)}
          onChange={(value) =>
            change(f.key, name === "concurrency" ? value : `${value}ms`)
          }
        />
      );
    }
    if (f.type === "boolean")
      return (
        <Checkbox
          label={props.label}
          checked={Boolean(value)}
          onChange={(e) => change(f.key, e.currentTarget.checked)}
          disabled={mutation.isPending}
        />
      );
    if (f.type === "secret")
      return (
        <div>
          <PasswordInput
            {...props}
            autoComplete="new-password"
            value={String(value)}
            placeholder={
              f.configured && !destinationChanged
                ? t("serverSettings.secretSet")
                : t("serverSettings.secretEmpty")
            }
            onChange={(e) => {
              const text = e.currentTarget.value;
              if (text) change(f.key, text);
              else
                setValues((old) => {
                  const next = { ...old };
                  delete next[f.key];
                  return next;
                });
            }}
          />
          <Button
            variant="subtle"
            size="compact-sm"
            disabled={mutation.isPending || !connectionAdmin}
            onClick={() => change(f.key, "")}
          >
            {t("serverSettings.clearSecret")}
          </Button>
        </div>
      );
    if (f.type === "list")
      return (
        <SettingsList
          label={props.label}
          name={label}
          values={value as string[]}
          disabled={mutation.isPending}
          modified={Object.hasOwn(values, f.key)}
          onChange={(value) => change(f.key, value)}
        />
      );
    const choices: Record<string, string[]> = {
      client: ["qbittorrent"],
      hashes: ["all", "first", "skip"],
      auth: ["auto", "api-key", "session"],
    };
    if (choices[f.type])
      return (
        <Select
          {...props}
          allowDeselect={false}
          value={String(value)}
          data={choices[f.type].map((v) => ({
            value: v,
            label: t(`serverSettings.choices.${v}`),
          }))}
          onChange={(v) => v && change(f.key, v)}
        />
      );
    return (
      <TextInput
        {...props}
        type={f.type === "number" ? "number" : "text"}
        min={f.type === "number" ? 1 : undefined}
        step={f.type === "number" ? 1 : undefined}
        description={
          f.type === "duration" &&
          view.section !== "catalog" &&
          view.section !== "torrent"
            ? t("serverSettings.durationHelp")
            : undefined
        }
        value={String(value)}
        onChange={(e) =>
          change(
            f.key,
            f.type === "number"
              ? Number(e.currentTarget.value)
              : e.currentTarget.value,
          )
        }
      />
    );
  }
  function renderField(f: Field, className?: string) {
    return (
      <div key={f.key} className={className}>
        {field(f)}
        {view.section !== "covers" && f.overridden && (
          <small className="muted">{t("serverSettings.customized")}</small>
        )}
      </div>
    );
  }
  return (
    <>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (valid && !mutation.isPending) mutation.mutate(false);
        }}
      >
        {view.credentialsError && (
          <Alert color="red" role="alert" mb="md">
            {view.credentialsError}
          </Alert>
        )}
        {destinationChanged && (
          <Alert color="orange" role="alert" mb="md">
            {t("serverSettings.destinationChangeWarning")}
          </Alert>
        )}
        {view.section === "torrent" && !connectionAdmin && (
          <Alert color="blue" mb="md">
            {t("serverSettings.connectionAdminOnly")}
          </Alert>
        )}
        {groups.map((group) => (
          <fieldset
            className={`send-options-group${["events", "catalog", "covers"].includes(view.section) ? " settings-borderless" : ""}${view.section === "covers" ? " cover-parameters" : ""}`}
            key={group}
          >
            {group && <legend>{t(`serverSettings.groups.${group}`)}</legend>}
            <div className="server-settings-grid">
              {view.fields
                .filter((f) => groupOf(f) === group)
                .sort((a, b) => {
                  if (group !== "defaults") return 0;
                  const order = [
                    "torrent.download.start",
                    "torrent.qbittorrent.download.auto-management",
                    "torrent.download.save-path",
                    "torrent.rename.enabled",
                    "torrent.rename.pattern",
                    "torrent.qbittorrent.download.category",
                    "torrent.qbittorrent.download.tags",
                  ];
                  return order.indexOf(a.key) - order.indexOf(b.key);
                })
                .map((f) => {
                  const related =
                    group === "defaults"
                      ? dependentGroups.find((entry) =>
                          entry.keys.includes(f.key),
                        )
                      : undefined;
                  if (related && f.key !== related.keys[0]) return null;
                  if (related) {
                    return (
                      <fieldset
                        className="server-settings-related"
                        key={related.name}
                      >
                        <legend>
                          {t(`serverSettings.groups.${related.name}`)}
                        </legend>
                        {related.keys.map((key) => {
                          const member = view.fields.find(
                            (entry) => entry.key === key,
                          );
                          return member ? renderField(member) : null;
                        })}
                      </fieldset>
                    );
                  }
                  return renderField(
                    f,
                    f.key === "torrent.enabled" ||
                      f.key === "torrent.download.start"
                      ? "server-settings-wide"
                      : undefined,
                  );
                })}
            </div>
          </fieldset>
        ))}
        {!valid && (
          <Alert
            color="orange"
            variant="light"
            role="alert"
            className="cover-validation"
          >
            {t("covers.invalidOptions")}
          </Alert>
        )}
        <div className="action-row">
          <Button
            type="submit"
            loading={mutation.isPending}
            disabled={!valid || !Object.keys(values).length}
          >
            {t("serverSettings.save")}
          </Button>
          <Button
            variant="default"
            disabled={
              mutation.isPending ||
              (view.section === "torrent" && !connectionAdmin)
            }
            onClick={() => setConfirm(true)}
          >
            {t("serverSettings.restore")}
          </Button>
          {typeof actions === "function" ? actions(values) : actions}
        </div>
      </form>
      {secretKeyDialog && (
        <SecretKeyDialog
          onClose={() => {
            setSecretKeyDialog(false);
            mutation.reset();
          }}
        />
      )}
      <AppModal
        icon={RotateCcw}
        opened={confirm}
        onClose={() => setConfirm(false)}
        title={t("serverSettings.restore")}
      >
        <p>{t("serverSettings.restoreHelp")}</p>
        {view.section === "torrent" && (
          <p>{t("serverSettings.restoreDestinationHelp")}</p>
        )}
        <ModalActions>
          <Button variant="default" onClick={() => setConfirm(false)}>
            {t("import.cancel")}
          </Button>
          <Button
            loading={mutation.isPending}
            onClick={() => mutation.mutate(true)}
          >
            {t("serverSettings.restore")}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
