import { secureFetch } from "../auth/transport";
import { PageJump } from "../../components/PageJump";
import { Button, Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useLocale } from "../../locales/useLocale";
export interface Page<T> {
  items: T[];
  meta: {
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
    hasNext: boolean;
    hasPrevious: boolean;
  };
}
export class ActionError extends Error {
  constructor(
    public status: number,
    public details: string,
    public eventOperationId?: string | null,
  ) {
    super(details);
  }
}
export async function post<T>(path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await secureFetch(`/api${path}`, {
      method: "POST",
      headers: {
        Accept: "application/json",
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
  } catch {
    throw new ActionError(0, "");
  }
  const data = await response.json().catch(() => null);
  if (!response.ok)
    throw new ActionError(
      response.status,
      typeof data?.details === "string" ? data.details : "",
      response.headers.get("X-EPLSync-Operation-Id"),
    );
  if (data === null) throw new ActionError(0, "");
  return data;
}
export function Paging({
  meta,
  page,
  size,
  onPage,
  onSize,
}: {
  meta?: Page<unknown>["meta"];
  page: number;
  size: number;
  onPage: (n: number) => void;
  onSize: (n: number) => void;
}) {
  const { t } = useTranslation();
  const { number } = useLocale();
  return (
    <div className="pagination">
      <Select
        className="page-size-select"
        withCheckIcon={false}
        aria-label={t("downloads.pageSize")}
        value={String(size)}
        data={[10, 20, 50, 100, 200, 500, 1000].map(String)}
        onChange={(v) => onSize(Number(v) || 20)}
        allowDeselect={false}
      />
      <span>
        {t("pagination.records", { total: number(meta?.totalItems ?? 0) })}
      </span>
      <PageJump
        page={page}
        totalPages={meta?.totalPages ?? 0}
        disabled={!meta}
        onPage={onPage}
      />
      <Button
        variant="default"
        disabled={!meta?.hasPrevious}
        onClick={() => onPage(page - 1)}
      >
        {t("catalog.previous")}
      </Button>
      <Button
        variant="default"
        disabled={!meta?.hasNext}
        onClick={() => onPage(page + 1)}
      >
        {t("catalog.next")}
      </Button>
    </div>
  );
}
export const downloadStates = [
  "SUBMITTED",
  "ALREADY_EXISTS",
  "UNKNOWN",
  "QUEUED",
  "DOWNLOADING",
  "PAUSED",
  "CHECKING",
  "DOWNLOADED",
  "ERROR",
  "NOT_FOUND",
];
export const jobStates = [
  "QUEUED",
  "RUNNING",
  "RETRY_WAIT",
  "PAUSED",
  "COMPLETED",
  "CANCELLED",
];
export const itemStates = [
  "PENDING",
  "IN_FLIGHT",
  "ACCEPTED",
  "ALREADY_EXISTS",
  "SKIPPED",
  "FAILED",
  "CANCELLED",
];
export interface Job {
  type?: "DOWNLOAD" | "UPDATE";
  previousVersions?: string | null;
  cleanupTiming?: "immediate" | "afterDownload" | null;
  cleanup?: {
    waiting: number;
    blocked: number;
    requested: number;
    removed: number;
    cancelled: number;
  } | null;
  jobId: string;
  status: string;
  client: string;
  selectedBooks: number;
  selectedItems: number;
  processedItems: number;
  accepted: number;
  alreadyExists: number;
  skipped: number;
  failed: number;
  pending: number;
  inFlight: number;
  cancelled: number;
  concurrency: number;
  batchSize: number;
  interval: string;
  multipleHashes: string;
  createdAt: string;
  updatedAt: string;
  retryAt: string | null;
  message: string | null;
}
