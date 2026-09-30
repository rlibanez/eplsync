import { PageJump } from "../../components/PageJump";
import { Alert, Button, Select } from "@mantine/core";
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
  ) {
    super(details);
  }
}
export async function post<T>(path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
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
    );
  if (data === null) throw new ActionError(0, "");
  return data;
}
export function ActionFailure({ error }: { error: Error | null }) {
  const { t } = useTranslation();
  if (!error) return null;
  return (
    <Alert color="red" role="alert" title={t("downloads.failed")}>
      <p>
        {error instanceof ActionError && error.status
          ? t("downloads.httpError", { status: error.status })
          : t("downloads.uncertain")}
      </p>
      {error instanceof ActionError && error.details && <p>{error.details}</p>}
    </Alert>
  );
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
