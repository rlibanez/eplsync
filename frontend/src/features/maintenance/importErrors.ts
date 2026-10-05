import { ApiError } from "../../api/catalog";

export class PreviewError extends ApiError {
  constructor(
    status: number,
    public code: string,
    operationId?: string | null,
    details?: string,
  ) {
    super(status, operationId, details);
  }
}

/** Preserve the backend's safe rejection reason alongside preview codes and event identity. */
export async function readImportError(
  response: Response,
): Promise<PreviewError> {
  const body = await response.json().catch(() => null);
  return new PreviewError(
    response.status,
    typeof body?.code === "string" ? body.code : "",
    response.headers.get("X-EPLSync-Operation-Id"),
    typeof body?.details === "string" ? body.details : undefined,
  );
}
