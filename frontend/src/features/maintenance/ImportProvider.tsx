import { useNotifications } from "../notifications/Notifications";
import { useTranslation } from "react-i18next";
import type { Metadata } from "./CatalogMetadata";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  useRef,
  type ReactNode,
} from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError, NetworkError } from "../../api/catalog";
export type ImportMode =
  "preview" | "update" | "reset" | "apply" | "refresh" | "discard";
export type ImportSource =
  | { source: "URL"; url: string }
  | { source: "SAVED"; archiveId: string }
  | { source: "UPLOAD"; file: File };
export interface ImportResult {
  metadata?: Metadata;
  preview?: {
    token: string;
    expiresAt: string;
    sourceModifiedAt: string | null;
  };
  success: boolean;
  missingBooks?: number | null;
  recordsProcessed: number;
  errors: number;
  recordsUpdated: number;
  recordsCreated: number;
  recordsUnchanged: number;
}
export interface ResetResult {
  success: boolean;
  catalogBooks: number;
  downloads: number;
  jobs: number;
  jobItems: number;
  updatePlans: number;
  cleanupRecords: number;
  metadataRecords: number;
  events: number;
}
interface Operation {
  mode: ImportMode;
  pending: boolean;
  result?: ImportResult;
  resetResult?: ResetResult;
  error?: Error;
}
class PreviewError extends ApiError {
  constructor(
    status: number,
    public code: string,
    operationId?: string | null,
  ) {
    super(status, operationId);
  }
}
const storedTokenKey = "eplsync.catalogPreview";
const Context = createContext<{
  operation: Operation | null;
  preview: ImportResult | null;
  previewIssue: string | null;
  restoring: boolean;
  run: (mode: ImportMode, source?: ImportSource) => Promise<void>;
  dismissResult: () => void;
  dismissPreview: () => void;
  dismissReset: () => void;
} | null>(null);
export function ImportProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const { notify } = useNotifications();
  const { t } = useTranslation();
  const [operation, setOperation] = useState<Operation | null>(null);
  const [preview, setPreview] = useState<ImportResult | null>(null);
  const [previewIssue, setPreviewIssue] = useState<string | null>(null);
  const [restoring, setRestoring] = useState(true);
  const running = useRef(false);
  function remember(value: ImportResult | null) {
    setPreview(value);
    try {
      if (value?.preview)
        sessionStorage.setItem(storedTokenKey, value.preview.token);
      else sessionStorage.removeItem(storedTokenKey);
    } catch {
      /* Navigation still works when browser storage is unavailable. */
    }
  }
  useEffect(() => {
    const abort = new AbortController();
    let token: string | null = null;
    try {
      token = sessionStorage.getItem(storedTokenKey);
    } catch {
      /* optional storage */
    }
    if (!token) {
      setRestoring(false);
      return;
    }
    void fetch(`/api/catalog/import/preview/${encodeURIComponent(token)}`, {
      signal: abort.signal,
    })
      .then(async (response) => {
        if (response.ok) {
          const result: ImportResult = await response.json();
          if (!abort.signal.aborted) remember(result);
        } else if (response.status === 410 && !abort.signal.aborted)
          remember(null);
      })
      .catch(() => {
        /* Keep the token so a later reload can recover it. */
      })
      .finally(() => {
        if (!abort.signal.aborted) setRestoring(false);
      });
    return () => abort.abort();
  }, []);
  useEffect(() => {
    if (!operation?.pending) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [operation?.pending]);
  async function run(mode: ImportMode, source?: ImportSource) {
    if (running.current || restoring) return;
    const retainedAction = ["apply", "refresh", "discard"].includes(mode);
    const token = preview?.preview?.token;
    if (retainedAction && !token) return;
    running.current = true;
    setOperation({ mode, pending: true });
    setPreviewIssue(null);
    if ((mode === "preview" || mode === "update") && source) remember(null);
    const title = t(
      mode === "reset"
        ? "reset.title"
        : mode === "preview" || mode === "refresh"
          ? "import.preview"
          : mode === "discard"
            ? "import.discard"
            : "import.update",
    );
    const href = "/settings/database";
    try {
      let response: Response;
      try {
        if (source && (mode === "preview" || mode === "update")) {
          const dryRun = mode === "preview";
          if (source.source === "UPLOAD") {
            const body = new FormData();
            body.append("file", source.file);
            body.append(
              "options",
              new Blob([JSON.stringify({ dryRun })], {
                type: "application/json",
              }),
            );
            response = await fetch("/api/catalog/import/run", {
              method: "POST",
              headers: { Accept: "application/json" },
              body,
            });
          } else {
            response = await fetch("/api/catalog/import/run", {
              method: "POST",
              headers: {
                Accept: "application/json",
                "Content-Type": "application/json",
              },
              body: JSON.stringify({ ...source, dryRun }),
            });
          }
        } else {
          response = await fetch(
            mode === "reset"
              ? "/api/maintenance/reset"
              : retainedAction
                ? `/api/catalog/import/preview/${mode}`
                : "/api/catalog/import/run",
            {
              method: "POST",
              headers: {
                Accept: "application/json",
                "Content-Type": "application/json",
              },
              ...(mode === "reset"
                ? { body: JSON.stringify({ confirm: true }) }
                : retainedAction
                  ? { body: JSON.stringify({ token }) }
                  : {
                      body: JSON.stringify({
                        source: "URL",
                        dryRun: mode === "preview",
                      }),
                    }),
            },
          );
        }
      } catch {
        throw new NetworkError("Import response unavailable");
      }
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new PreviewError(
          response.status,
          body.code ?? "",
          response.headers.get("X-EPLSync-Operation-Id"),
        );
      }
      if (mode === "discard") {
        remember(null);
        setOperation(null);
      } else if (mode === "reset") {
        const resetResult: ResetResult = await response.json();
        remember(null);
        setOperation({ mode, pending: false, resetResult });
        notify({ title, message: t("reset.done"), tone: "success", href });
      } else {
        const result: ImportResult = await response.json();
        if (mode === "preview" || mode === "refresh") remember(result);
        else if (mode === "apply") remember(null);
        setOperation({ mode, pending: false, result });
        if (
          (mode === "preview" || mode === "refresh") &&
          !response.headers.get("X-EPLSync-Operation-Id")
        )
          notify({
            title,
            message: t(result.errors ? "import.partial" : "import.previewDone"),
            tone: result.errors ? "warning" : "success",
            href,
          });
      }
    } catch (error) {
      const code = error instanceof PreviewError ? error.code : "";
      if (code.startsWith("PREVIEW_") || code === "ARCHIVE_EXPIRED") {
        setPreviewIssue(code);
        if (code === "PREVIEW_EXPIRED" || code === "ARCHIVE_EXPIRED")
          remember(null);
      }
      const message =
        code.startsWith("PREVIEW_") ||
        code === "ARCHIVE_EXPIRED" ||
        code === "ZIP_TOO_LARGE"
          ? t(`import.previewErrors.${code}`)
          : t(
              error instanceof ApiError && error.status === 409
                ? "reset.busy"
                : error instanceof NetworkError
                  ? "import.networkError"
                  : error instanceof ApiError
                    ? "import.httpError"
                    : "import.unexpectedError",
              { status: error instanceof ApiError ? error.status : "" },
            );
      if (!(error instanceof ApiError && error.eventOperationId))
        notify({
          title,
          message:
            message +
            (["apply", "update", "reset"].includes(mode) &&
            !code.startsWith("PREVIEW_") &&
            code !== "ARCHIVE_EXPIRED" &&
            code !== "ZIP_TOO_LARGE" &&
            !(error instanceof ApiError && error.status === 409)
              ? "\n" +
                t(mode === "reset" ? "reset.uncertain" : "import.uncertain")
              : ""),
          tone: "error",
          href,
        });
      setOperation({
        mode,
        pending: false,
        error: error instanceof Error ? error : new Error("Import failed"),
      });
    } finally {
      if (["reset", "update", "apply"].includes(mode)) {
        for (const key of [
          "catalog",
          "book",
          "catalog-metadata",
          "directory",
          "send-preview",
          "magnets",
        ])
          void client.invalidateQueries({ queryKey: [key] });
      }
      if (mode === "reset") {
        for (const key of [
          "events",
          "event-operations",
          "event-unread",
          "cover-task",
          "downloads",
          "download-summary",
          "jobs",
          "job",
          "job-items",
        ])
          void client.resetQueries({ queryKey: [key] });
      }
      running.current = false;
    }
  }
  return (
    <Context.Provider
      value={{
        operation,
        preview,
        previewIssue,
        restoring,
        run,
        dismissResult: () =>
          setOperation((current) => (current?.pending ? current : null)),
        dismissPreview: () => {
          void run("discard");
        },
        dismissReset: () =>
          setOperation((current) =>
            current?.mode === "reset" && !current.pending ? null : current,
          ),
      }}
    >
      {children}
    </Context.Provider>
  );
}
export function useImport() {
  const value = useContext(Context);
  if (!value) throw new Error("ImportProvider is required");
  return value;
}
