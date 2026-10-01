import { CurrentCatalog } from "./CatalogMetadata";
import { useEffect, useState } from "react";
import { Alert, Button, Loader, Modal } from "@mantine/core";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Download, Eye } from "lucide-react";
import { useImport } from "./ImportProvider";
import { useLocale } from "../../locales/useLocale";
import { ApiError, NetworkError } from "../../api/catalog";
export function ImportCatalog() {
  const { t } = useTranslation();
  const { number } = useLocale();
  const { operation, run } = useImport();
  const [confirm, setConfirm] = useState<"update" | "reset" | null>(null);
  const [noticeVisible, setNoticeVisible] = useState(false);
  useEffect(() => {
    setNoticeVisible(true);
    if (!operation?.result?.success && !operation?.resetResult?.success) return;
    if (operation?.result?.errors) return;
    const timer = window.setTimeout(() => setNoticeVisible(false), 5000);
    return () => window.clearTimeout(timer);
  }, [operation]);
  const pending = operation?.pending ?? false;
  return (
    <>
      <CurrentCatalog />
      <section className="panel settings-section">
        <h2>{t("import.source")}</h2>
        <p>{t("import.sourceDescription")}</p>
        <p className="muted">{t("import.behaviour")}</p>
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
        <p className="muted import-note">{t("import.previewNote")}</p>
      </section>
      <section className="panel settings-section danger-panel">
        <h2>{t("reset.title")}</h2>
        <p>{t("reset.description")}</p>
        <p className="muted">{t("reset.kept")}</p>
        <Button
          color="red"
          variant="light"
          disabled={pending}
          onClick={() => setConfirm("reset")}
        >
          {t("reset.action")}
        </Button>
      </section>
      {pending && operation && (
        <Alert
          title={t(
            operation.mode === "preview"
              ? "import.previewPending"
              : operation.mode === "reset"
                ? "reset.pending"
                : "import.updatePending",
          )}
          icon={<Loader size="sm" />}
          role="status"
        >
          {t("import.pendingDescription")}
        </Alert>
      )}
      {operation?.error && (
        <Alert color="red" title={t("import.failed")} role="alert">
          <p>
            {t(
              operation.error instanceof ApiError &&
                operation.error.status === 409
                ? "reset.busy"
                : operation.error instanceof NetworkError
                  ? "import.networkError"
                  : operation.error instanceof ApiError
                    ? "import.httpError"
                    : "import.unexpectedError",
              {
                status:
                  operation.error instanceof ApiError
                    ? operation.error.status
                    : "",
              },
            )}
          </p>
          {operation.mode === "update" && <p>{t("import.uncertain")}</p>}
          {operation.mode === "reset" &&
            !(
              operation.error instanceof ApiError &&
              operation.error.status === 409
            ) && <p>{t("reset.uncertain")}</p>}
        </Alert>
      )}
      {operation?.result && operation.mode !== "preview" && noticeVisible && (
        <Alert
          withCloseButton
          closeButtonLabel={t("covers.close")}
          onClose={() => setNoticeVisible(false)}
          color={
            !operation.result.success
              ? "red"
              : operation.result.errors
                ? "yellow"
                : "teal"
          }
          role="status"
        >
          {t(
            !operation.result.success
              ? "import.unsuccessful"
              : operation.result.errors
                ? "import.partial"
                : "import.updateDone",
          )}
        </Alert>
      )}
      {operation?.result && operation.mode === "preview" && (
        <section className="panel settings-section" aria-live="polite">
          <h2>
            {t(
              operation.mode === "preview"
                ? "import.previewResult"
                : "import.updateResult",
            )}
          </h2>
          {noticeVisible && (
            <Alert
              withCloseButton
              closeButtonLabel={t("covers.close")}
              onClose={() => setNoticeVisible(false)}
              color={
                !operation.result.success
                  ? "red"
                  : operation.result.errors
                    ? "yellow"
                    : "teal"
              }
            >
              {t(
                !operation.result.success
                  ? "import.unsuccessful"
                  : operation.result.errors
                    ? "import.partial"
                    : operation.mode === "preview"
                      ? "import.previewDone"
                      : "import.updateDone",
              )}
            </Alert>
          )}
          <dl className="import-summary">
            {(
              [
                "recordsProcessed",
                "recordsCreated",
                "recordsUpdated",
                "recordsUnchanged",
                "errors",
              ] as const
            ).map((key) => (
              <div key={key}>
                <dt>{t(`import.${key}`)}</dt>
                <dd>{number(operation.result![key])}</dd>
              </div>
            ))}
          </dl>
          <Link className="back-link" to="/catalog">
            {t("nav.explore")}
          </Link>
        </section>
      )}
      {operation?.resetResult && (
        <section className="panel settings-section" aria-live="polite">
          <h2>{t("reset.result")}</h2>
          {noticeVisible && (
            <Alert
              withCloseButton
              closeButtonLabel={t("covers.close")}
              onClose={() => setNoticeVisible(false)}
              color={operation.resetResult.success ? "teal" : "red"}
            >
              {t(
                operation.resetResult.success
                  ? "reset.done"
                  : "import.unsuccessful",
              )}
            </Alert>
          )}
          <dl className="import-summary">
            {(
              [
                "catalogBooks",
                "downloads",
                "jobs",
                "jobItems",
                "updatePlans",
                "cleanupRecords",
                "recordsImported",
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
      <Modal
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
        <div className="action-row">
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
        </div>
      </Modal>
    </>
  );
}
