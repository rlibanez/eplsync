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
  success: boolean;
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
} | null>(null);
export function ImportProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
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
      if (!response.ok) throw new ApiError(response.status);
      if (mode === "reset") {
        const resetResult: ResetResult = await response.json();
        setOperation({ mode, pending: false, resetResult });
      } else {
        const result: ImportResult = await response.json();
        setOperation({ mode, pending: false, result });
      }
    } catch (error) {
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
        for (const key of ["directory", "send-preview", "magnets"])
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
    <Context.Provider value={{ operation, run }}>{children}</Context.Provider>
  );
}
export function useImport() {
  const value = useContext(Context);
  if (!value) throw new Error("ImportProvider is required");
  return value;
}
