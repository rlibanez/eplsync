import { useEffect, useRef, useState } from "react";
import { useLocation } from "react-router-dom";
import { Button, Checkbox, PasswordInput } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { ApiError, NetworkError, get } from "../../api/catalog";
import { secureFetch } from "../auth/transport";
import { Failure, Loading } from "../../components/Feedback";
import { useSettingsSaved } from "./useSettingsSaved";
import { SecretKeyDialog } from "./SecretKeyDialog";
import { formatDate } from "../../locales/formatDate";

type Usage = {
  limit: number;
  remaining: number;
  used: number;
  observedAt: string;
  resetAt: string;
};
type View = {
  apiKeyConfigured: boolean;
  environmentConfigured: boolean;
  overridden: boolean;
  usage?: Usage | null;
};
async function request<T>(
  method: string,
  body?: unknown,
  path = "",
): Promise<T> {
  let response: Response;
  try {
    response = await secureFetch(`/api/settings/hardcover${path}`, {
      method,
      headers:
        body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new NetworkError();
  }
  const data = await response.json().catch(() => null);
  if (!response.ok)
    throw Object.assign(new ApiError(response.status, null, data?.details), {
      code: data?.code,
    });
  if (data === null) throw new ApiError(response.status);
  return data as T;
}
export function HardcoverSettings() {
  const { t, i18n } = useTranslation();
  const { hash } = useLocation();
  const section = useRef<HTMLElement>(null);
  const field = useRef<HTMLInputElement>(null);
  const cache = useQueryClient();
  const saved = useSettingsSaved();
  const query = useQuery({
    queryKey: ["hardcover-settings"],
    queryFn: ({ signal }) => get<View>("/settings/hardcover", signal),
    refetchOnWindowFocus: false,
  });
  const [apiKey, setApiKey] = useState("");
  const [clearApiKey, setClearApiKey] = useState(false);
  const [keyDialog, setKeyDialog] = useState(false);
  const input = () => ({ apiKey, clearApiKey });
  const applied = (data: View) => {
    cache.setQueryData(["hardcover-settings"], data);
    setApiKey("");
    setClearApiKey(false);
    check.reset();
    saved();
  };
  const save = useMutation({
    mutationFn: () => request<View>("PUT", input()),
    onSuccess: applied,
    onError: (error) => {
      if ((error as { code?: string }).code === "SECRET_KEY_REQUIRED")
        setKeyDialog(true);
    },
  });
  const check = useMutation({
    mutationFn: () =>
      request<{ connected: boolean; usage?: Usage | null }>(
        "POST",
        input(),
        "/connection",
      ),
    onError: () => {
      void query.refetch();
    },
  });
  const busy = save.isPending || check.isPending;
  const reset = () => {
    save.reset();
    check.reset();
  };
  useEffect(() => {
    if (hash === "#hardcover" && query.isSuccess && !query.isError) {
      section.current?.scrollIntoView({ block: "start" });
      field.current?.focus({ preventScroll: true });
    }
  }, [hash, query.isSuccess, query.isError]);
  if (query.isPending) return <Loading />;
  if (query.isError)
    return <Failure error={query.error} retry={() => query.refetch()} />;
  const initial = query.data;
  const usage = check.isSuccess
    ? check.data.usage
    : !apiKey && !clearApiKey
      ? initial.usage
      : null;
  return (
    <section
      id="hardcover"
      ref={section}
      className="panel settings-section"
      aria-label="Hardcover"
    >
      <h2>Hardcover</h2>
      {!initial.apiKeyConfigured && (
        <p role="status">{t("hardcoverSettings.notConfigured")}</p>
      )}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!busy) save.mutate();
        }}
      >
        <div className="settings-fields-grid">
          <PasswordInput
            ref={field}
            label={t("hardcoverSettings.apiKey")}
            value={apiKey}
            autoComplete="new-password"
            data-bwignore="true"
            maxLength={8192}
            disabled={busy}
            placeholder={
              initial.apiKeyConfigured ? t("hardcoverSettings.keySaved") : ""
            }
            onChange={(event) => {
              setApiKey(event.currentTarget.value);
              setClearApiKey(false);
              reset();
            }}
          />
        </div>
        {(initial.apiKeyConfigured || apiKey) && (
          <div className="hardcover-usage" role="status">
            {usage ? (
              <>
                <p>
                  {t("hardcoverSettings.usage", {
                    used: usage.used,
                    limit: usage.limit,
                    remaining: usage.remaining,
                  })}
                </p>
                <small className="muted">
                  {t("hardcoverSettings.usageTime", {
                    observed: formatDate(usage.observedAt, i18n.language),
                    reset: formatDate(usage.resetAt, i18n.language),
                  })}
                </small>
              </>
            ) : (
              <small className="muted">
                {t("hardcoverSettings.usageUnknown")}
              </small>
            )}
          </div>
        )}
        <Checkbox
          label={t("hardcoverSettings.clearKey")}
          checked={clearApiKey}
          disabled={busy || !initial.apiKeyConfigured}
          onChange={(event) => {
            setClearApiKey(event.currentTarget.checked);
            setApiKey("");
            reset();
          }}
        />
        <div className="action-row">
          <Button
            type="submit"
            loading={save.isPending}
            disabled={busy || (!apiKey && !clearApiKey)}
          >
            {t("calibre.save")}
          </Button>
          <Button
            type="button"
            variant="light"
            loading={check.isPending}
            disabled={
              busy || clearApiKey || (!apiKey && !initial.apiKeyConfigured)
            }
            onClick={() => {
              save.reset();
              check.mutate();
            }}
          >
            {t("calibre.check")}
          </Button>
        </div>
      </form>
      <div style={{ marginTop: 12 }}>
        {check.isSuccess && (
          <small role="status">{t("hardcoverSettings.connected")}</small>
        )}
        {check.isError && (
          <Failure error={check.error} retry={() => check.mutate()} />
        )}
        {save.isError && (
          <Failure error={save.error} retry={() => save.mutate()} />
        )}
      </div>
      {keyDialog && <SecretKeyDialog onClose={() => setKeyDialog(false)} />}
    </section>
  );
}
