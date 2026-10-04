import { ServerSettings } from "../settings/ServerSettings";
import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { ImportWizard } from "./ImportWizard";
import { MissingBooks } from "./MissingBooks";
import { CurrentCatalog } from "./CatalogMetadata";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ActionIcon, Button, Loader, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Download, X } from "lucide-react";
import { useImport } from "./ImportProvider";
import { useLocale } from "../../locales/useLocale";
export function ImportCatalog() {
  const { t } = useTranslation();
  const { number } = useLocale();
  const {
    operation,
    preview,
    previewIssue,
    restoring,
    run,
    dismissPreview,
    dismissReset,
    dismissResult,
  } = useImport();
  const [confirm, setConfirm] = useState<"reset" | null>(null);
  const [wizard, setWizard] = useState(false);
  const [search, setSearch] = useSearchParams();
  useEffect(() => {
    if (search.get("import") === "true") {
      setWizard(true);
      const next = new URLSearchParams(search);
      next.delete("import");
      setSearch(next, { replace: true });
    }
  }, [search, setSearch]);
  const pending = restoring || (operation?.pending ?? false);
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
                operation?.mode === "preview" || operation?.mode === "refresh"
                  ? "import.previewPending"
                  : operation?.mode === "apply"
                    ? "import.applyPending"
                    : operation?.mode === "discard"
                      ? "import.discardPending"
                      : "import.updatePending",
              )}
            </span>
          </div>
        )}
        <p>{t("import.sourceDescription")}</p>
        {preview && (
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
                  disabled={pending}
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
                    {preview[key] == null
                      ? t("metadata.unknown")
                      : number(preview[key]!)}
                  </dd>
                </div>
              ))}
              {preview.preview && (
                <div>
                  <dt>{t("import.csvDate")}</dt>
                  <dd>
                    {preview.preview.sourceModifiedAt?.replace("T", " ") ??
                      t("metadata.unknown")}
                  </dd>
                </div>
              )}
            </dl>
            {previewIssue && (
              <p role="alert">{t(`import.previewErrors.${previewIssue}`)}</p>
            )}
            <div className="action-row">
              <Button
                disabled={
                  pending ||
                  !preview.preview ||
                  previewIssue === "PREVIEW_STALE" ||
                  previewIssue === "PREVIEW_FILE_CHANGED"
                }
                onClick={() => void run("apply")}
              >
                {t("import.apply")}
              </Button>
              <Button
                variant="default"
                disabled={pending || !preview.preview}
                onClick={dismissPreview}
              >
                {t("import.discard")}
              </Button>
              {previewIssue === "PREVIEW_STALE" && (
                <Button
                  variant="default"
                  disabled={pending}
                  onClick={() => void run("refresh")}
                >
                  {t("import.refreshPreview")}
                </Button>
              )}
            </div>
          </section>
        )}
        {operation?.result &&
          !operation.pending &&
          ["update", "apply"].includes(operation.mode) && (
            <section className="import-preview-result" aria-live="polite">
              <div className="import-preview-heading">
                <h3>{t("import.result")}</h3>
                <Tooltip label={t("import.closeResult")}>
                  <ActionIcon
                    variant="subtle"
                    aria-label={t("import.closeResult")}
                    onClick={dismissResult}
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
            leftSection={<Download size={17} />}
            disabled={pending}
            onClick={() => setWizard(true)}
          >
            {t("import.wizardTitle")}
          </Button>
        </div>
      </section>
      <section className="panel settings-section">
        <h2>{t("missing.sectionTitle")}</h2>
        <p>{t("missing.sectionDescription")}</p>
        <MissingBooks disabled={pending} />
      </section>
      <ServerSettings section="catalog" />
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
                  "settingsRecords",
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
      <ImportWizard
        opened={wizard}
        onClose={() => setWizard(false)}
        onStart={(mode, source) => {
          void run(mode, source);
        }}
      />
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
