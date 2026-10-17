import { describe, expect, it } from "vitest";
import { operationOutcome, formatDuration } from "./eventTypes";

describe("job control audit operations", () => {
  it.each(["PAUSED", "RESUMED", "RECOVERED", "CANCELLED"])(
    "closes a successful %s command without changing job events",
    (outcome) => {
      expect(operationOutcome(outcome, "CALIBRE_JOB_CONTROL")).toBe(
        "SUCCEEDED",
      );
      expect(operationOutcome(outcome, "CALIBRE_SEND")).toBe(
        ["RESUMED", "RECOVERED"].includes(outcome) ? "STARTED" : outcome,
      );
    },
  );
  it("preserves failures and incomplete control requests", () => {
    expect(operationOutcome("FAILED", "CALIBRE_JOB_CONTROL")).toBe("FAILED");
    expect(operationOutcome("STARTED", "CALIBRE_JOB_CONTROL")).toBe("STARTED");
  });
  it("renders a measured zero as seconds", () => {
    expect(formatDuration(0, "es")).toBe("0 s");
  });
});
