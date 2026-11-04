import { get, ApiError } from "../../api/catalog";
import { post, type Job, ActionError } from "./shared";
export interface TorrentPreparation {
  id: string;
  state: "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  phase: string;
  processed: number;
  total: number;
  items: number;
  jobId: string | null;
  result: Job | null;
  message: string | null;
}
export class TorrentPreparationCancelled extends Error {}
export class TorrentPreparationUnknown extends Error {}
/** Polling recovers a lost POST response; it never resubmits the creation request. */
export async function prepareTorrent(
  url: string,
  body: unknown,
  id: string,
  update: (progress: TorrentPreparation) => void,
): Promise<Job> {
  const request = post<Job>(url, body, undefined, {
    "X-EPLSync-Preparation-Id": id,
  }).then(
    (value) => ({ value }),
    (error) => ({ error: error as Error }),
  );
  let response: { value?: Job; error?: Error } | undefined;
  let consecutiveUnavailable = 0;
  let lastSeen = Date.now();
  while (true) {
    if (!response) {
      const next = await Promise.race([
        request,
        new Promise<null>((resolve) => setTimeout(() => resolve(null), 1000)),
      ]);
      if (next) response = next;
    } else await new Promise((resolve) => setTimeout(resolve, 1000));
    if (response?.value) return response.value;
    // Validation and authorization failures happen before progress registration.
    if (
      response?.error instanceof ActionError &&
      [401, 403, 413].includes(response.error.status)
    )
      throw response.error;
    try {
      const progress = await get<TorrentPreparation>(
        `/torrent/preparations/${encodeURIComponent(id)}`,
        AbortSignal.timeout(5000),
      );
      consecutiveUnavailable = 0;
      lastSeen = Date.now();
      update(progress);
      if (progress.state === "COMPLETED" && progress.result)
        return progress.result;
      if (progress.state === "CANCELLED")
        throw new TorrentPreparationCancelled();
      if (progress.state === "FAILED") {
        if (response?.error) throw response.error;
        throw new ActionError(400, progress.message ?? "");
      }
    } catch (error) {
      if (
        error instanceof ActionError ||
        error instanceof TorrentPreparationCancelled
      )
        throw error;
      if (
        error instanceof ApiError &&
        error.status === 404 &&
        response?.error instanceof ActionError &&
        [400, 422].includes(response.error.status)
      )
        throw response.error;
      if (error instanceof ApiError && [401, 403].includes(error.status))
        throw error;
      consecutiveUnavailable++;
      if (
        consecutiveUnavailable >= 3 &&
        (response?.error || Date.now() - lastSeen >= 30_000)
      )
        throw new TorrentPreparationUnknown();
    }
  }
}
