import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { MissingBooks } from "./MissingBooks";
import { CurrentCatalog } from "./CatalogMetadata";
import { useState } from "react";
import { ActionIcon, Button, Loader, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Download, Eye, X } from "lucide-react";
import { useImport } from "./ImportProvider";
import { useLocale } from "../../locales/useLocale";
export function ImportCatalog() {
  const { t } = useTranslation();
  const { number } = useLocale();
  const { operation, run, dismissPreview, dismissReset } = useImport();
  const [confirm, setConfirm] = useState<"update" | "reset" | null>(null);
  const pending = operation?.pending ?? false;
  return (
    <>
      <CurrentCatalog />
      <section className="panel settings-section">
        <h2>{t("import.source")}</h2>
        {pending && operation?.mode !== "reset" && (
          <div role="status" className="import-progress">
            <Loader size="sm" />
            <span>
              {t(
                operation?.mode === "preview"
                  ? "import.previewPending"
                  : "import.updatePending",
              )}
            </span>
          </div>
        )}
        <p>{t("import.sourceDescription")}</p>
        <p className="muted">{t("import.previewNote")}</p>
        {operation?.result && operation.mode === "preview" && (
          <section
            className="import-preview-result"
            aria-live="polite"
            aria-labelledby="import-preview-heading"
          >
            <div className="import-preview-heading">
              <h3 id="import-preview-heading">{t("import.previewResult")}</h3>
              <Tooltip label={t("import.closePreview")}>
                <ActionIcon
                  variant="subtle"
                  aria-label={t("import.closePreview")}
                  onClick={dismissPreview}
                >
                  <X size={18} />
                </ActionIcon>
              </Tooltip>
            </div>
            <dl className="import-summary">
              {(
                [
                  "recordsProcessed",
                  "recordsCreated",
                  "recordsUpdated",
                  "recordsUnchanged",
                  "errors",
                  "missingBooks",
                ] as const
              ).map((key) => (
                <div key={key}>
                  <dt>{t(`import.${key}`)}</dt>
                  <dd>
                    {operation.result![key] == null
                      ? t("metadata.unknown")
                      : number(operation.result![key]!)}
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        )}
        <div className="action-row">
          <Button
            leftSection={<Eye size={17} />}
            variant="default"
            disabled={pending}
            onClick={() => void run("preview")}
          >
            {t("import.preview")}
          </Button>
          <Button
            leftSection={<Download size={17} />}
            disabled={pending}
            onClick={() => setConfirm("update")}
          >
            {t("import.update")}
          </Button>
        </div>
      </section>
      <section className="panel settings-section">
        <h2>{t("missing.sectionTitle")}</h2>
        <p>{t("missing.sectionDescription")}</p>
        <MissingBooks disabled={pending} />
      </section>
      <section className="panel settings-section danger-panel">
        <h2>{t("reset.title")}</h2>
        {pending && operation?.mode === "reset" && (
          <div role="status" className="import-progress">
            <Loader size="sm" />
            <span>{t("reset.pending")}</span>
          </div>
        )}
        <p>{t("reset.description")}</p>
        {operation?.resetResult && (
          <section
            className="import-preview-result reset-result"
            aria-live="polite"
            aria-labelledby="reset-result-heading"
          >
            <div className="import-preview-heading">
              <h3 id="reset-result-heading">{t("reset.result")}</h3>
              <Tooltip label={t("reset.closeResult")}>
                <ActionIcon
                  variant="subtle"
                  aria-label={t("reset.closeResult")}
                  onClick={dismissReset}
                >
                  <X size={18} />
                </ActionIcon>
              </Tooltip>
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
                  "events",
                ] as const
              ).map((key) => (
                <div key={key}>
                  <dt>{t(`reset.${key}`)}</dt>
                  <dd>{number(operation.resetResult![key])}</dd>
                </div>
              ))}
            </dl>
          </section>
        )}
        <Button
          color="red"
          variant="light"
          disabled={pending}
          onClick={() => setConfirm("reset")}
        >
          {t("reset.action")}
        </Button>
      </section>
      <Modal
        icon={Download}
        opened={confirm !== null}
        onClose={() => setConfirm(null)}
        title={t(
          confirm === "reset" ? "reset.confirmTitle" : "import.confirmTitle",
        )}
        centered
      >
        <p>
          {t(
            confirm === "reset"
              ? "reset.confirmDescription"
              : "import.confirmDescription",
          )}
        </p>
        <ModalActions>
          <Button variant="default" onClick={() => setConfirm(null)}>
            {t("import.cancel")}
          </Button>
          <Button
            color={confirm === "reset" ? "red" : undefined}
            disabled={pending}
            onClick={() => {
              const mode = confirm;
              setConfirm(null);
              if (mode) void run(mode);
            }}
          >
            {t(confirm === "reset" ? "reset.confirm" : "import.confirm")}
          </Button>
        </ModalActions>
      </Modal>
    </>
  );
}
