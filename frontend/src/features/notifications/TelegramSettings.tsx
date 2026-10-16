import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Checkbox,
  Group,
  PasswordInput,
  TextInput,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/Auth";
import { authRequest } from "../auth/transport";
import { Failure, Loading } from "../../components/Feedback";

type Preferences = {
  enabled: boolean;
  tokenConfigured: boolean;
  chatId: string;
  success: boolean;
  warning: boolean;
  error: boolean;
  language: string;
  pending: number;
  failed: number;
};
type Draft = Preferences & { token: string; clearToken: boolean };
export function TelegramSettings() {
  const { t, i18n } = useTranslation();
  const { user } = useAuth();
  const cache = useQueryClient();
  const key = ["telegram-settings", user?.id];
  const query = useQuery({
    queryKey: key,
    queryFn: () => authRequest<Preferences>("/auth/telegram"),
  });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saved, setSaved] = useState(false);
  const save = useMutation({
    mutationFn: (value: Draft) =>
      authRequest<Preferences>("/auth/telegram", "PUT", {
        enabled: value.enabled,
        token: value.token,
        clearToken: value.clearToken,
        chatId: value.chatId,
        success: value.success,
        warning: value.warning,
        error: value.error,
        language: i18n.resolvedLanguage?.startsWith("es") ? "es" : "en",
      }),
    onSuccess: (value) => {
      cache.setQueryData(key, value);
      setDraft(null);
      setSaved(true);
      test.reset();
    },
  });
  const test = useMutation({
    mutationFn: (value: Draft) =>
      authRequest<void>("/auth/telegram/test", "POST", {
        token: value.token,
        clearToken: value.clearToken,
        chatId: value.chatId,
      }),
  });
  if (query.isPending) return <Loading />;
  if (query.isError)
    return <Failure error={query.error} retry={() => query.refetch()} />;
  const value = draft ?? { ...query.data, token: "", clearToken: false };
  const change = (patch: Partial<Draft>) => {
    setDraft({ ...value, ...patch });
    setSaved(false);
    save.reset();
    test.reset();
  };
  const busy = save.isPending || test.isPending;
  return (
    <section className="panel settings-section telegram-settings">
      <h2>Telegram</h2>
      <div className="telegram-settings-content">
        <Checkbox
          label={t("telegram.enabled")}
          checked={value.enabled}
          disabled={busy}
          onChange={(e) => change({ enabled: e.currentTarget.checked })}
        />
        <div className="telegram-connection-grid">
          <PasswordInput
            label={t("telegram.token")}
            value={value.token}
            maxLength={222}
            disabled={busy || value.clearToken}
            placeholder={
              value.tokenConfigured ? t("telegram.tokenSaved") : "123456789:…"
            }
            onChange={(e) => change({ token: e.currentTarget.value })}
          />
          <TextInput
            label={t("telegram.chat")}
            value={value.chatId}
            maxLength={21}
            disabled={busy}
            onChange={(e) => change({ chatId: e.currentTarget.value })}
          />
        </div>
        {value.tokenConfigured && (
          <Checkbox
            label={t("telegram.clearToken")}
            checked={value.clearToken}
            disabled={busy}
            onChange={(e) =>
              change({
                clearToken: e.currentTarget.checked,
                token: "",
                enabled: e.currentTarget.checked ? false : value.enabled,
              })
            }
          />
        )}
        <div className="telegram-results">
          <h3 id="telegram-results-label">{t("notifications.results")}</h3>
          <div
            className="notification-checkboxes"
            role="group"
            aria-labelledby="telegram-results-label"
          >
            {(["success", "warning", "error"] as const).map((field) => (
              <Checkbox
                key={field}
                label={t("telegram." + field)}
                checked={value[field]}
                disabled={busy}
                onChange={(e) => change({ [field]: e.currentTarget.checked })}
              />
            ))}
          </div>
        </div>
        <Group className="telegram-actions">
          <Button
            loading={save.isPending}
            disabled={test.isPending || draft === null}
            onClick={() => save.mutate(value)}
          >
            {t("auth.save")}
          </Button>
          <Button
            variant="default"
            loading={test.isPending}
            disabled={
              save.isPending ||
              (!value.token.trim() &&
                (!value.tokenConfigured || value.clearToken)) ||
              !value.chatId.trim()
            }
            onClick={() => test.mutate(value)}
          >
            {t("telegram.test")}
          </Button>
        </Group>
        {saved && (
          <Alert color="green" role="status">
            {t("telegram.saved")}
          </Alert>
        )}
        {test.isSuccess && (
          <Alert color="green" role="status">
            {t("telegram.testOk")}
          </Alert>
        )}
        {(save.isError || test.isError) && (
          <Alert color="red" role="alert">
            {(save.error ?? test.error)?.message}
          </Alert>
        )}
        {(value.pending > 0 || value.failed > 0) && (
          <p className="muted">
            {t("telegram.deliveryStatus", {
              pending: value.pending,
              failed: value.failed,
            })}
          </p>
        )}
      </div>
    </section>
  );
}
