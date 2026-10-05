export interface AppEvent {
  id: number;
  createdAt: string;
  category: "CATALOG" | "JOB" | "COVERS" | "TORRENT" | "SECURITY";
  action: string;
  outcome: string;
  origin: string;
  operationId: string;
  details: Record<string, string | number | boolean>;
}
export interface EventPage {
  items: AppEvent[];
  total: number;
  page: number;
  size: number;
  cursor: number;
}
export const eventHref = (event: AppEvent) =>
  event.action === "SEND_BOOK" && typeof event.details.eplId === "number"
    ? "/catalog/" + event.details.eplId
    : event.category === "CATALOG"
      ? "/settings/database"
      : event.category === "COVERS"
        ? "/settings/covers"
        : event.category === "JOB"
          ? "/downloads/jobs/" + encodeURIComponent(event.operationId)
          : "/downloads";
export const eventTone = (event: AppEvent) =>
  event.outcome === "FAILED"
    ? "error"
    : ["PARTIAL", "PAUSED", "RETRY_WAIT", "CANCELLED"].includes(event.outcome)
      ? "warning"
      : event.outcome === "SUCCEEDED"
        ? "success"
        : "info";
export function dateBounds(from: string, to: string) {
  const bound = (value: string, next: boolean) => {
    const [y, m, d] = value.split("-").map(Number);
    return new Date(y, m - 1, d + (next ? 1 : 0)).toISOString();
  };
  return {
    from: from ? bound(from, false) : undefined,
    before: to ? bound(to, true) : undefined,
  };
}

export interface EventOperation {
  latest: AppEvent;
  startedAt: string | null;
  finishedAt: string | null;
  firstRecordedAt: string;
  durationMs: number | null;
  events: AppEvent[];
}
export interface OperationPage {
  items: EventOperation[];
  total: number;
  page: number;
  size: number;
  cursor: number;
}
export const operationOutcome = (outcome: string) =>
  ["STARTED", "RESUMED", "RECOVERED"].includes(outcome) ? "STARTED" : outcome;
export function formatDuration(ms: number, locale?: string) {
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 });
  if (ms < 60000) return number.format(ms / 1000) + " s";
  const seconds = Math.floor(ms / 1000);
  return (
    (seconds >= 3600 ? Math.floor(seconds / 3600) + " h " : "") +
    Math.floor((seconds % 3600) / 60) +
    " min " +
    (seconds % 60) +
    " s"
  );
}
