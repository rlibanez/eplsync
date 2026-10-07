import { useAuth } from "../auth/Auth";
import { CoverParameter, coverKeys, validCoverOptions } from "./CoverParameter";
import { ServerSettings } from "./ServerSettings";
import { OptionLabel } from "../downloads/SendOptions";
import { useState } from "react";
import { Alert, Button, Checkbox, Progress } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { post } from "../downloads/shared";
import { Loading, Failure } from "../../components/Feedback";
import {
  type CoverOptions,
  type CoverTask,
  useCoverTask,
} from "../catalog/coverApi";

export function CoverSettings() {
  const auth = useAuth();
  const defaults = useQuery({
    queryKey: ["cover-config"],
    queryFn: ({ signal }) =>
      get<CoverOptions>("/catalog/covers/config", signal),
  });
  if (defaults.isPending) return <Loading />;
  if (defaults.isError)
    return <Failure error={defaults.error} retry={() => defaults.refetch()} />;
  return (
    <>
      <CoverSettingsForm
        key={JSON.stringify(defaults.data)}
        defaults={defaults.data}
      />
      {auth.can("SETTINGS_MANAGE") && <ServerSettings section="covers" />}
    </>
  );
}

function CoverSettingsForm({ defaults }: { defaults: CoverOptions }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [options, setOptions] = useState(defaults);
  const [dryRun, setDryRun] = useState(false);
  const [includeReviewed, setIncludeReviewed] = useState(true);
  const status = useCoverTask();
  const task = status.data?.task;
  const start = useMutation({
    meta: {
      backendEvents: true,
      notice: {
        title: "covers.title",
        success: "covers.running",
        error: "covers.startError",
        href: "/settings/catalog",
      },
    },
    retry: false,
    mutationFn: () =>
      post<CoverTask>("/catalog/covers/task", {
        dryRun,
        onlyUnchecked: !includeReviewed,
        options,
      }),
    onSuccess: (task) => cache.setQueryData(["cover-task"], { task }),
    onError: () => {
      void status.refetch();
    },
  });
  const busy = start.isPending || task?.state === "RUNNING";
  const displayedOptions = task?.state === "RUNNING" ? task.options : options;
  const valid = validCoverOptions(options);
  return (
    <section className="panel settings-section">
      <h2>{t("covers.title")}</h2>
      <p>{t("covers.description")}</p>
      <p className="muted">{t("covers.parametersNote")}</p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (valid && !busy) start.mutate();
        }}
      >
        <fieldset
          disabled={busy}
          className="send-options-group cover-parameters settings-borderless"
        >
          <legend className="sr-only">{t("covers.parameters")}</legend>
          <div className="server-settings-grid">
            {coverKeys.map((key) => (
              <CoverParameter
                key={key}
                name={key}
                value={displayedOptions[key]}
                disabled={busy}
                onChange={(value) =>
                  setOptions((old) => ({ ...old, [key]: value }))
                }
              />
            ))}
          </div>
        </fieldset>
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
        <Checkbox
          className="cover-scope"
          label={
            <OptionLabel
              text={t("covers.includeReviewed")}
              help={t("covers.includeReviewedHelp")}
            />
          }
          checked={
            task?.state === "RUNNING" ? !task.onlyUnchecked : includeReviewed
          }
          disabled={busy}
          onChange={(event) => setIncludeReviewed(event.currentTarget.checked)}
        />
        <Checkbox
          label={t("covers.dryRun")}
          checked={task?.state === "RUNNING" ? task.dryRun : dryRun}
          disabled={busy}
          onChange={(event) => setDryRun(event.currentTarget.checked)}
        />
        <div className="action-row cover-start">
          <Button
            type="submit"
            disabled={!valid || busy || status.isPending || status.isError}
            loading={start.isPending}
          >
            {t("covers.start")}
          </Button>
          <Button
            variant="default"
            disabled={busy}
            onClick={() => setOptions(defaults)}
          >
            {t("covers.restoreDefaults")}
          </Button>
        </div>
      </form>
      {status.isError && (
        <Alert color="red" role="alert">
          <p>{t("covers.statusError")}</p>
          <Button variant="light" onClick={() => void status.refetch()}>
            {t("covers.retryStatus")}
          </Button>
        </Alert>
      )}
      {task && (
        <div className="cover-task-result" aria-live="polite">
          <h3>{t(`covers.states.${task.state}`)}</h3>
          <p>{t(task.dryRun ? "covers.testMode" : "covers.saveMode")}</p>
          <p>
            {t(
              task.onlyUnchecked ? "covers.scopeUnchecked" : "covers.scopeAll",
            )}
          </p>
          {task.state === "RUNNING" && (
            <>
              <Progress
                value={
                  task.total
                    ? Math.min(100, (task.checked * 100) / task.total)
                    : 0
                }
                aria-label={t("covers.progress")}
              />
              <p>
                {t("covers.progressCount", {
                  checked: task.checked,
                  total: task.total,
                })}
              </p>
              {task.total > 0 && task.checked >= task.total && (
                <p>{t("covers.finishing")}</p>
              )}
              <p className="muted">{t("covers.background")}</p>
            </>
          )}
          {task.summary && (
            <dl className="cover-summary">
              {(
                [
                  "checked",
                  "available",
                  "unavailable",
                  "inconclusive",
                  "wouldChange",
                  "updated",
                ] as const
              ).map((key) => (
                <div key={key}>
                  <dt>{t(`covers.summary.${key}`)}</dt>
                  <dd>{task.summary![key]}</dd>
                </div>
              ))}
            </dl>
          )}
        </div>
      )}
    </section>
  );
}
