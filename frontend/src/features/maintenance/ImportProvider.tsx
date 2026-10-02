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
export type ImportMode = "preview" | "update" | "reset";
export interface ImportResult {
  metadata?: Metadata;
  success: boolean;
  recordsProcessed: number;
  errors: number;
  recordsUpdated: number;
  recordsCreated: number;
  recordsUnchanged: number;
}
export interface ResetResult {
  metadata?: Metadata;
  success: boolean;
  catalogBooks: number;
  downloads: number;
  jobs: number;
  jobItems: number;
  updatePlans: number;
  cleanupRecords: number;
  recordsImported: number;
}
interface Operation {
  mode: ImportMode;
  pending: boolean;
  result?: ImportResult;
  resetResult?: ResetResult;
  error?: Error;
}
const Context = createContext<{
  operation: Operation | null;
  run: (mode: ImportMode) => Promise<void>;
  dismissPreview: () => void;
} | null>(null);
export function ImportProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const { notify } = useNotifications();
  const { t } = useTranslation();
  const [operation, setOperation] = useState<Operation | null>(null);
  const running = useRef(false);
  useEffect(() => {
    if (!operation?.pending) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [operation?.pending]);
  async function run(mode: ImportMode) {
    if (running.current) return;
    running.current = true;
    setOperation({ mode, pending: true });
    const title = t(
      mode === "reset"
        ? "reset.title"
        : mode === "preview"
          ? "import.preview"
          : "import.update",
    );
    const href = "/settings/database";
    if (mode === "preview")
      notify({
        title,
        message: t("import.pendingDescription"),
        tone: "info",
        href,
      });
    try {
      let response: Response;
      try {
        response = await fetch(
          mode === "reset"
            ? "/api/maintenance/reset"
            : `/api/catalog/import/${mode}`,
          {
            method: "POST",
            headers: {
              Accept: "application/json",
              ...(mode === "reset"
                ? { "Content-Type": "application/json" }
                : {}),
            },
            ...(mode === "reset"
              ? { body: JSON.stringify({ confirm: true }) }
              : {}),
          },
        );
      } catch {
        throw new NetworkError("Import response unavailable");
      }
      if (!response.ok)
        throw new ApiError(
          response.status,
          response.headers.get("X-EPLSync-Operation-Id"),
        );
      if (mode === "reset") {
        const resetResult: ResetResult = await response.json();
        setOperation({ mode, pending: false, resetResult });
      } else {
        const result: ImportResult = await response.json();
        setOperation({ mode, pending: false, result });
        if (mode === "preview")
          notify({
            title,
            message: t(
              !result.success
                ? "import.unsuccessful"
                : result.errors
                  ? "import.partial"
                  : mode === "preview"
                    ? "import.previewDone"
                    : "import.updateDone",
            ),
            tone: !result.success
              ? "error"
              : result.errors
                ? "warning"
                : "success",
            href,
          });
      }
    } catch (error) {
      const message = t(
        error instanceof ApiError && error.status === 409
          ? "reset.busy"
          : error instanceof NetworkError
            ? "import.networkError"
            : error instanceof ApiError
              ? "import.httpError"
              : "import.unexpectedError",
        { status: error instanceof ApiError ? error.status : "" },
      );
      if (
        mode === "preview" ||
        !(error instanceof ApiError && error.eventOperationId)
      )
        notify({
          title,
          message:
            message +
            (mode !== "preview" &&
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
      // An interrupted response can still correspond to a committed update.
      if (mode === "reset") {
        void client.resetQueries({ queryKey: ["catalog"] });
        void client.resetQueries({ queryKey: ["book"] });
      } else if (mode === "update") {
        void client.invalidateQueries({ queryKey: ["catalog"] });
        void client.invalidateQueries({ queryKey: ["book"] });
      }
      if (mode === "reset" || mode === "update") {
        for (const key of [
          "catalog-metadata",
          "directory",
          "send-preview",
          "magnets",
        ])
          void client.resetQueries({ queryKey: [key] });
      }
      if (mode === "reset") {
        for (const key of [
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
        run,
        dismissPreview: () =>
          setOperation((current) =>
            current?.mode === "preview" && !current.pending ? null : current,
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
