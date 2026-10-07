import { useAuth } from "../auth/Auth";
import { useState } from "react";
import { Button, Checkbox } from "@mantine/core";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { authRequest } from "../auth/transport";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
const states = [
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
];
const defaults = () => [...states];
interface Preferences {
  states: string[];
}
export function DownloadSettings() {
  const query = useQuery({
    queryKey: ["update-preferences"],
    queryFn: ({ signal }) =>
      get<Preferences>("/settings/downloads/updates", signal),
  });
  if (query.isPending) return <Loading />;
  if (query.isError)
    return <Failure error={query.error} retry={() => query.refetch()} />;
  return <UpdateSettingsForm initial={query.data} />;
}
function UpdateSettingsForm({ initial }: { initial: Preferences }) {
  const { t } = useTranslation();
  const canEdit = useAuth().can("SETTINGS_MANAGE");
  const { status } = useLocale();
  const cache = useQueryClient();
  const [selected, setSelected] = useState(initial.states);
  const save = useMutation({
    mutationFn: () =>
      authRequest<Preferences>("/settings/downloads/updates", "PUT", {
        states: selected,
      }),
    onSuccess: (data) => cache.setQueryData(["update-preferences"], data),
  });
  return (
    <section className="panel settings-section">
      <h2>{t("updateSettings.title")}</h2>
      <p>{t("updateSettings.description")}</p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (canEdit && selected.length) save.mutate();
        }}
      >
        <fieldset
          className="send-options-group"
          disabled={save.isPending || !canEdit}
        >
          <legend>{t("updateSettings.states")}</legend>
          <div className="settings-fields-grid">
            {states.map((value) => (
              <Checkbox
                key={value}
                label={status(value)}
                checked={selected.includes(value)}
                onChange={(e) => {
                  const checked = e.currentTarget.checked;
                  save.reset();
                  setSelected((old) =>
                    checked ? [...old, value] : old.filter((s) => s !== value),
                  );
                }}
              />
            ))}
          </div>
        </fieldset>
        <div className="action-row">
          <Button
            type="submit"
            loading={save.isPending}
            disabled={!canEdit || !selected.length}
          >
            {t("updateSettings.save")}
          </Button>
          <Button
            variant="default"
            disabled={save.isPending || !canEdit}
            onClick={() => {
              setSelected(defaults());
              save.reset();
            }}
          >
            {t("updateSettings.reset")}
          </Button>
        </div>
      </form>
      {save.isError && (
        <Failure error={save.error} retry={() => save.mutate()} />
      )}
      {save.isSuccess && (
        <small className="muted" role="status">{t("updateSettings.saved")}</small>
      )}
    </section>
  );
}
