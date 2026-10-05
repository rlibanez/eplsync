import { describe, expect, it } from "vitest";
import { readImportError } from "./importErrors";
import { ApiError } from "../../api/catalog";

describe("catalog import rejection details", () => {
  it("preserves safe details and event identity for validation errors", async () => {
    const response = new Response(
      JSON.stringify({ details: "El ZIP supera el máximo de 128 MiB" }),
      {
        status: 400,
        headers: { "X-EPLSync-Operation-Id": "operation-1" },
      },
    );
    const error = await readImportError(response);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.details).toBe("El ZIP supera el máximo de 128 MiB");
    expect(error.eventOperationId).toBe("operation-1");
    expect(error.status).toBe(400);
  });
  it("retains known codes for localized preview messages", async () => {
    const error = await readImportError(
      new Response(JSON.stringify({ code: "PREVIEW_EXPIRED" }), {
        status: 409,
      }),
    );
    expect(error.code).toBe("PREVIEW_EXPIRED");
  });
  it("falls back safely for non-JSON or malformed error fields", async () => {
    for (const body of [
      "<html>Unavailable</html>",
      JSON.stringify({ code: {}, details: ["private"] }),
      "null",
    ]) {
      const error = await readImportError(new Response(body, { status: 500 }));
      expect(error.code).toBe("");
      expect(error.details).toBeUndefined();
    }
  });
});
