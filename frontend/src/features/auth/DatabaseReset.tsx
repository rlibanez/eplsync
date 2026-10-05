import { Trash2, X } from "lucide-react";
import { useState } from "react";
import { Alert, Button, Checkbox, TextInput, ActionIcon } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { AppModal, ModalActions } from "../../components/AppModal";
import { authRequest } from "./transport";
import { useNotifications } from "../notifications/Notifications";
import type { ResetResult } from "../maintenance/ImportProvider";
import { useAuth } from "./Auth";
export function DatabaseReset() {
  const { t } = useTranslation();
  const { notify } = useNotifications();
  const auth = useAuth();
  const cache = useQueryClient();
  const [opened, setOpened] = useState(false);
  const [erase, setErase] = useState(false);
  const [confirmation, setConfirmation] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<ResetResult>();
  async function reset() {
    setPending(true);
    setError("");
    try {
      const result = await authRequest<ResetResult>(
        "/maintenance/reset",
        "POST",
        {
          confirm: true,
          eraseUsersAndSettings: erase,
          fullResetConfirmation: confirmation,
        },
      );
      cache.clear();
      setOpened(false);
      setResult(result);
      notify({
        title: t("reset.title"),
        message: t("reset.done"),
        tone: "success",
      });
      if (erase) await auth.refresh();
    } catch (e) {
      const message =
        e instanceof Error && e.message.includes("MAINTENANCE_BUSY")
          ? t("reset.busy")
          : t("reset.uncertain");
      setError(message);
      notify({ title: t("reset.title"), message, tone: "error" });
    } finally {
      setPending(false);
    }
  }
  return (
    <section className="panel settings-section danger-panel">
      <h2>{t("reset.title")}</h2>
      <p>{t("reset.description")}</p>
      {error && <Alert color="red">{error}</Alert>}
      {result && (
        <section
          className="import-preview-result reset-result"
          aria-live="polite"
        >
          <div className="import-preview-heading">
            <h3>{t("reset.result")}</h3>
            <ActionIcon
              aria-label={t("reset.closeResult")}
              onClick={() => setResult(undefined)}
            >
              <X size={18} />
            </ActionIcon>
          </div>
          <dl className="import-summary">
            {(
              [
                "catalogBooks",
                "downloads",
                "jobs",
                "jobItems",
                "updatePlans",
                "cleanupRecords",
                "metadataRecords",
                "settingsRecords",
                "events",
              ] as const
            ).map((key) => (
              <div key={key}>
                <dt>{t(`reset.${key}`)}</dt>
                <dd>{result[key] ?? 0}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
      <Button
        color="red"
        disabled={pending}
        onClick={() => {
          setErase(false);
          setConfirmation("");
          setOpened(true);
        }}
      >
        {t("reset.action")}
      </Button>
      <AppModal
        icon={Trash2}
        opened={opened}
        onClose={() => {
          if (!pending) setOpened(false);
        }}
        title={t("reset.confirmTitle")}
        centered
      >
        <p>{t("reset.confirmDescription")}</p>
        <Checkbox
          label={t("auth.eraseUsersAndSettings")}
          checked={erase}
          disabled={pending}
          onChange={(e) => setErase(e.currentTarget.checked)}
        />
        {erase && (
          <>
            <Alert color="red">{t("auth.fullResetWarning")}</Alert>
            <TextInput
              label="BORRAR TODO"
              value={confirmation}
              disabled={pending}
              onChange={(e) => setConfirmation(e.currentTarget.value)}
            />
          </>
        )}
        <ModalActions>
          <Button
            variant="default"
            disabled={pending}
            onClick={() => setOpened(false)}
          >
            {t("import.cancel")}
          </Button>
          <Button
            color="red"
            loading={pending}
            disabled={erase && confirmation !== "BORRAR TODO"}
            onClick={() => void reset()}
          >
            {t("reset.confirm")}
          </Button>
        </ModalActions>
      </AppModal>
    </section>
  );
}
