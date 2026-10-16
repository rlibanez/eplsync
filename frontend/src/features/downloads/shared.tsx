import {
  PageSizeSelect,
  type PageSizeChoice,
} from "../../components/PageSizeSelect";
import { secureFetch } from "../auth/transport";
import { PageJump } from "../../components/PageJump";
import { Button } from "@mantine/core";
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
export async function post<T>(
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  let response: Response;
  try {
    response = await secureFetch(`/api${path}`, {
      method: "POST",
      signal,
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
  maxSize = 1000,
  choice,
  disabled = false,
}: {
  meta?: Page<unknown>["meta"];
  page: number;
  size: number;
  onPage: (n: number) => void;
  onSize: (n: number) => void;
  maxSize?: number;
  choice: PageSizeChoice;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const { number } = useLocale();
  return (
    <div className="pagination">
      <PageSizeSelect
        disabled={disabled}
        size={size}
        total={meta?.totalItems}
        onSize={onSize}
        choice={choice}
        maximum={maxSize}
        label={t("downloads.pageSize")}
      />
      <span>
        {t("pagination.records", { total: number(meta?.totalItems ?? 0) })}
      </span>
      <PageJump
        page={page}
        totalPages={meta?.totalPages ?? 0}
        disabled={disabled || !meta}
        onPage={onPage}
      />
      <Button
        variant="default"
        disabled={disabled || !meta?.hasPrevious}
        onClick={() => onPage(page - 1)}
      >
        {t("catalog.previous")}
      </Button>
      <Button
        variant="default"
        disabled={disabled || !meta?.hasNext}
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
  "CLEANUP_WAIT",
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
  type?: "DOWNLOAD" | "UPDATE" | "CLEANUP";
  deleteHistory?: boolean;
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
  username?: string | null;
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
