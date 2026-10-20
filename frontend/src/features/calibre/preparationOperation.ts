import { get, NetworkError } from "../../api/catalog";
import { post } from "../downloads/shared";
export type PreparationProgress = {
  id: string;
  kind: string;
  state: string;
  phase: string;
  processed: number;
  total: number;
  startedAt: number;
  message: string | null;
  result: unknown;
};
export class PreparationFailure extends Error {}
export class PreparationCancelled extends Error {}
/** Polling never repeats the POST; the server's request ID makes an explicit retry idempotent. */
export async function preparationOperation<T>(
  path: string,
  input: unknown,
  report: (value: PreparationProgress) => void,
): Promise<T> {
  let operation = await post<PreparationProgress>(path, input);
  for (;;) {
    report(operation);
    if (operation.state === "CANCELLED") throw new PreparationCancelled();
    if (operation.state === "COMPLETED") return operation.result as T;
    if (operation.state === "FAILED")
      throw new PreparationFailure(
        operation.message ?? "No se pudo preparar el envío",
      );
    await new Promise((resolve) => window.setTimeout(resolve, 1000));
    for (let attempt = 0; ; attempt++) {
      try {
        operation = await get<PreparationProgress>(
          `/calibre/transfers/catalog/operations/${encodeURIComponent(operation.id)}`,
        );
        break;
      } catch (error) {
        if (!(error instanceof NetworkError) || attempt >= 2) throw error;
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
      }
    }
  }
}
