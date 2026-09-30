import { useEffect, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { get } from "../../api/catalog";

export interface CoverOptions {
  connectTimeoutMs: number;
  requestTimeoutMs: number;
  batchTimeoutMs: number;
  concurrency: number;
}
export interface CoverResult {
  eplId: number;
  coverUrl: string;
  previousAvailable: boolean | null;
  available: boolean | null;
  httpStatus: number | null;
  reason: string;
  wouldChange: boolean;
  updated: boolean;
}
export interface CoverReport {
  dryRun: boolean;
  checked: number;
  available: number;
  unavailable: number;
  inconclusive: number;
  wouldChange: number;
  updated: number;
  items: CoverResult[];
}
export interface CoverTask {
  id: string;
  state: "RUNNING" | "COMPLETED" | "FAILED";
  dryRun: boolean;
  onlyUnchecked: boolean;
  options: CoverOptions;
  checked: number;
  total: number;
  summary: CoverReport | null;
  error: string | null;
}
export function useCoverTask() {
  return useQuery({
    queryKey: ["cover-task"],
    queryFn: ({ signal }) =>
      get<{ task: CoverTask | null }>("/catalog/covers/task", signal),
    refetchInterval: (query) =>
      query.state.data?.task?.state === "RUNNING" ? 2000 : false,
    retry: false,
  });
}
// Keep completion visible to the cache even after leaving Settings.
export function useCoverTaskCompletion(task?: CoverTask | null) {
  const cache = useQueryClient();
  const handled = useRef<string | null>(null);
  useEffect(() => {
    if (
      task?.state === "COMPLETED" &&
      !task.dryRun &&
      handled.current !== task.id
    ) {
      handled.current = task.id;
      void cache.invalidateQueries({ queryKey: ["book"] });
      void cache.invalidateQueries({ queryKey: ["catalog"] });
    }
  }, [task, cache]);
}
