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
import { useLocale } from "../../locales/useLocale";

interface View {
  url: string;
  username: string;
  passwordConfigured: boolean;
  library: string;
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
  const { date } = useLocale();
  const [url, setUrl] = useState(initial.url);
  const [username, setUsername] = useState(initial.username);
  const [password, setPassword] = useState("");
  const [clearPassword, setClearPassword] = useState(false);
  const [library, setLibrary] = useState(initial.library);
  const [keyDialog, setKeyDialog] = useState(false);
  const input = () => ({ url, username, password, clearPassword, library });
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
      void cache.invalidateQueries({ queryKey: ["catalog"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
      setUrl(data.url);
      setUsername(data.username);
      setLibrary(data.library);
      setPassword("");
      setClearPassword(false);
    },
    onError: (error) => {
      if ((error as { code?: string }).code === "SECRET_KEY_REQUIRED")
        setKeyDialog(true);
    },
  });
  const snapshot = useQuery({
    queryKey: ["calibre-snapshot"],
    queryFn: ({ signal }) =>
      get<{
        snapshot: {
          library: string;
          syncedAt: string;
          total: number;
          identified: number;
        } | null;
      }>("/settings/calibre/snapshot", signal).then((data) => data.snapshot),
  });
  const sync = useMutation({
    mutationFn: () =>
      request<{
        library: string;
        syncedAt: string;
        total: number;
        identified: number;
      }>("POST", {}, "/sync"),
    retry: false,
    onSuccess: (data) => {
      cache.setQueryData(["calibre-snapshot"], data);
      void cache.invalidateQueries({ queryKey: ["catalog"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
    },
  });
  const busy = sync.isPending || check.isPending || save.isPending;
  const changed =
    url !== initial.url ||
    username !== initial.username ||
    password !== "" ||
    clearPassword ||
    library !== initial.library;
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
        {check.isSuccess && (
          <small role="status">
            {t("calibre.connected", { count: check.data.libraries.length })}
          </small>
        )}
        {save.isSuccess && <small role="status">{t("calibre.saved")}</small>}
        {check.isError && (
          <Failure error={check.error} retry={() => check.mutate()} />
        )}
        {save.isError && (
          <Failure error={save.error} retry={() => save.mutate()} />
        )}
      </section>
      {initial.library && (
        <section className="panel settings-section">
          <h2>{t("calibre.catalogSync")}</h2>
          <p>{t("calibre.syncDescription")}</p>
          <div className="action-row">
            <Button
              disabled={busy || changed}
              loading={sync.isPending}
              onClick={() => sync.mutate()}
            >
              {t("calibre.sync")}
            </Button>
          </div>
          {snapshot.data && (
            <p>
              {t("calibre.syncSummary", {
                total: snapshot.data.total,
                identified: snapshot.data.identified,
                date: date(snapshot.data.syncedAt),
              })}
            </p>
          )}
          {!snapshot.data && !snapshot.isError && (
            <p>{t("calibre.notSynced")}</p>
          )}
          {snapshot.isError && (
            <Failure error={snapshot.error} retry={() => snapshot.refetch()} />
          )}
          {sync.isError && (
            <Failure error={sync.error} retry={() => sync.mutate()} />
          )}
          {sync.isSuccess && <small role="status">{t("calibre.synced")}</small>}
        </section>
      )}
      {keyDialog && <SecretKeyDialog onClose={() => setKeyDialog(false)} />}
    </>
  );
}
