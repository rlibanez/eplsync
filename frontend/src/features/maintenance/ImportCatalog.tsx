import { useQuery } from "@tanstack/react-query";
import { get } from "../../api/catalog";
import { CoverSettings } from "../settings/CoverSettings";
import { useAuth } from "../auth/Auth";
import { ServerSettings } from "../settings/ServerSettings";
import { ImportWizard } from "./ImportWizard";
import { MissingBooks } from "./MissingBooks";
import { CurrentCatalog } from "./CatalogMetadata";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ActionIcon, Button, Progress, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Download, X } from "lucide-react";
import { useImport } from "./ImportProvider";
import { useLocale } from "../../locales/useLocale";
export function ImportCatalog({ guided = false }: { guided?: boolean }) {
  const { t } = useTranslation();
  const auth = useAuth();
  const { number } = useLocale();
  const {
    operation,
    preview,
    previewIssue,
    restoring,
    run,
    dismissPreview,
    dismissResult,
  } = useImport();
  const [wizard, setWizard] = useState(false);
  const [search, setSearch] = useSearchParams();
  useEffect(() => {
    if (auth.can("CATALOG_IMPORT") && search.get("import") === "true") {
      setWizard(true);
      const next = new URLSearchParams(search);
      next.delete("import");
      setSearch(next, { replace: true });
    }
  }, [search, setSearch]);
  const pending = restoring || (operation?.pending ?? false);
  const progress = useQuery({
    queryKey: ["catalog-import-progress", operation?.startedAt],
    queryFn: ({ signal }) =>
      get<{
        active: boolean;
        phase: string;
        processedRows: number;
        totalRows?: number;
      }>("/catalog/import/progress", signal),
    enabled:
      auth.can("CATALOG_IMPORT") &&
      !!operation?.pending &&
      operation.mode !== "reset",
    refetchInterval: operation?.pending ? 1000 : false,
    retry: false,
  });
  const totalRows = progress.data?.totalRows ?? 0;
  const percent =
    totalRows > 0
      ? Math.min(100, (100 * (progress.data?.processedRows ?? 0)) / totalRows)
      : undefined;
  return (
    <>
      {auth.can("CATALOG_READ") && <CurrentCatalog />}
      {auth.can("CATALOG_IMPORT") && (
        <section className="panel settings-section">
          <h2>{t("import.source")}</h2>
          {pending && operation?.mode !== "reset" && (
            <div role="status" className="import-progress">
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
              <Progress.Root
                role="progressbar"
                aria-label={t("import.progressLabel")}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={percent}
                data-indeterminate={percent === undefined || undefined}
              >
                <Progress.Section
                  value={percent ?? 35}
                  animated
                  withAria={false}
                  className="import-progress-indicator"
                />
              </Progress.Root>
              {progress.data?.active && (
                <span>
                  {t(`import.progressPhases.${progress.data.phase}`)}
                  {progress.data.totalRows
                    ? ` · ${t("import.countProgress", { processed: number(progress.data.processedRows), total: number(progress.data.totalRows) })}`
                    : progress.data.processedRows > 0
                      ? ` · ${t("import.processedProgress", { count: progress.data.processedRows, number: number(progress.data.processedRows) })}`
                      : ""}
                </span>
              )}
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
                    <X size={16} />
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
                      <X size={16} />
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
              leftSection={<Download size={16} />}
              disabled={pending}
              onClick={() => setWizard(true)}
            >
              {t("import.wizardTitle")}
            </Button>
          </div>
        </section>
      )}
      {!guided && auth.can("CATALOG_DELETE") && (
        <section className="panel settings-section">
          <h2>{t("missing.sectionTitle")}</h2>
          <p>{t("missing.sectionDescription")}</p>
          <MissingBooks disabled={pending} />
        </section>
      )}
      {!guided && auth.can("SETTINGS_MANAGE") && (
        <ServerSettings section="catalog" />
      )}
      {!guided && auth.can("COVERS_MANAGE") && <CoverSettings />}
      {auth.can("CATALOG_IMPORT") && (
        <ImportWizard
          opened={wizard}
          onClose={() => setWizard(false)}
          onStart={(mode, source) => {
            void run(mode, source);
          }}
        />
      )}
    </>
  );
}
