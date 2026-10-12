import { useSettingsSaved } from "./useSettingsSaved";
import { OptionLabel } from "../downloads/SendOptions";
import { useState } from "react";
import {
  Button,
  Checkbox,
  PasswordInput,
  Select,
  TextInput,
} from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get, ApiError, NetworkError } from "../../api/catalog";
import { secureFetch } from "../auth/transport";
import { Loading, Failure } from "../../components/Feedback";
import { SecretKeyDialog } from "./SecretKeyDialog";

interface View {
  url: string;
  username: string;
  passwordConfigured: boolean;
  library: string;
  browserUrl?: string;
}
interface Check {
  connected: boolean;
  libraries: { id: string; name: string }[];
  defaultLibrary: string;
}
async function request<T>(
  method: string,
  body: unknown,
  path = "",
): Promise<T> {
  let response: Response;
  try {
    response = await secureFetch(`/api/settings/calibre${path}`, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
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
  return data;
}
export function CalibreSettings() {
  const query = useQuery({
    queryKey: ["calibre-settings"],
    queryFn: ({ signal }) => get<View>("/settings/calibre", signal),
    refetchOnWindowFocus: false,
  });
  if (query.isPending) return <Loading />;
  if (query.isError)
    return <Failure error={query.error} retry={() => query.refetch()} />;
  return <Form initial={query.data} />;
}
function Form({ initial }: { initial: View }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const settingsSaved = useSettingsSaved();
  const [browserUrl, setBrowserUrl] = useState(initial.browserUrl ?? "");
  const [url, setUrl] = useState(initial.url);
  const [username, setUsername] = useState(initial.username);
  const [password, setPassword] = useState("");
  const [clearPassword, setClearPassword] = useState(false);
  const [library, setLibrary] = useState(initial.library);
  const [keyDialog, setKeyDialog] = useState(false);
  const input = () => ({
    url,
    username,
    password,
    clearPassword,
    library,
    browserUrl,
  });
  const check = useMutation({
    mutationFn: () => request<Check>("POST", input(), "/connection"),
    onMutate: (): void => {
      save.reset();
    },
    retry: false,
    onSuccess: (data) => {
      if (!data.libraries.some((l) => l.id === library))
        setLibrary(data.defaultLibrary || data.libraries[0]?.id || "");
    },
  });
  const save = useMutation({
    mutationFn: () => request<View>("PUT", input()),
    onMutate: (): void => {
      check.reset();
    },
    retry: false,
    onSuccess: (data) => {
      cache.setQueryData(["calibre-settings"], data);
      void cache.invalidateQueries({ queryKey: ["calibre-snapshot"] });
      void cache.invalidateQueries({ queryKey: ["calibre-library"] });
      void cache.invalidateQueries({ queryKey: ["catalog"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
      setUrl(data.url);
      setBrowserUrl(data.browserUrl ?? "");
      setUsername(data.username);
      setLibrary(data.library);
      setPassword("");
      setClearPassword(false);
      settingsSaved();
    },
    onError: (error) => {
      if ((error as { code?: string }).code === "SECRET_KEY_REQUIRED")
        setKeyDialog(true);
    },
  });
  const busy = check.isPending || save.isPending;
  const reset = () => {
    check.reset();
    save.reset();
  };
  return (
    <>
      <section className="panel settings-section">
        <h2>{t("calibre.configuration")}</h2>
        <p>{t("calibre.description")}</p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <fieldset className="send-options-group" disabled={busy}>
            <legend>{t("calibre.connection")}</legend>
            <div className="settings-fields-grid">
              <TextInput
                required
                label={t("calibre.url")}
                value={url}
                placeholder="http://calibre:8081"
                onChange={(e) => {
                  setUrl(e.currentTarget.value);
                  setLibrary("");
                  reset();
                }}
              />
              <TextInput
                label={
                  <OptionLabel
                    text={t("calibre.browserUrl")}
                    help={t("calibre.browserUrlHelp")}
                  />
                }
                value={browserUrl}
                onChange={(e) => {
                  setBrowserUrl(e.currentTarget.value);
                  reset();
                }}
              />
              <TextInput
                label={t("calibre.username")}
                value={username}
                autoComplete="off"
                onChange={(e) => {
                  setUsername(e.currentTarget.value);
                  reset();
                }}
              />
              <PasswordInput
                label={t("calibre.password")}
                value={password}
                autoComplete="new-password"
                placeholder={
                  initial.passwordConfigured ? t("calibre.passwordSaved") : ""
                }
                onChange={(e) => {
                  setPassword(e.currentTarget.value);
                  setClearPassword(false);
                  reset();
                }}
              />
              <Select
                label={t("calibre.library")}
                value={library || null}
                allowDeselect={false}
                data={
                  check.data?.libraries.map((l) => ({
                    value: l.id,
                    label: l.name,
                  })) ?? (library ? [{ value: library, label: library }] : [])
                }
                disabled={!check.data}
                onChange={(v) => {
                  setLibrary(v ?? "");
                  save.reset();
                }}
              />
            </div>
            {initial.passwordConfigured && (
              <Checkbox
                mt="md"
                label={t("calibre.clearPassword")}
                checked={clearPassword}
                onChange={(e) => {
                  setClearPassword(e.currentTarget.checked);
                  setPassword("");
                  reset();
                }}
              />
            )}
          </fieldset>
          {(url !== initial.url || username !== initial.username) &&
            initial.passwordConfigured && (
              <p>{t("calibre.destinationChanged")}</p>
            )}
          <div className="action-row">
            <Button
              type="submit"
              loading={save.isPending}
              disabled={busy || !url || !library}
            >
              {t("calibre.save")}
            </Button>
            <Button
              type="button"
              variant="light"
              loading={check.isPending}
              disabled={busy || !url}
              onClick={() => check.mutate()}
            >
              {t("calibre.check")}
            </Button>
          </div>
        </form>
        <div style={{ marginTop: 12 }}>
          {check.isSuccess && (
            <small role="status">
              {t("calibre.connected", { count: check.data.libraries.length })}
            </small>
          )}

          {check.isError && (
            <Failure error={check.error} retry={() => check.mutate()} />
          )}
          {save.isError && (
            <Failure error={save.error} retry={() => save.mutate()} />
          )}
        </div>
      </section>
      {keyDialog && <SecretKeyDialog onClose={() => setKeyDialog(false)} />}
    </>
  );
}
