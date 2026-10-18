import { describe, expect, it } from "vitest";
import { workWaitingMode } from "./WorkWaiting";

describe("worker waiting explanations", () => {
  it("only promises automatic progress for active workers", () => {
    for (const job of ["QUEUED", "RUNNING", "RETRY_WAIT", "CLEANUP_WAIT"])
      for (const item of ["PENDING", "WAITING_DOWNLOAD", "CLEANUP_PENDING"])
        expect(workWaitingMode(item, job)).toBe("automatic");
    expect(workWaitingMode("PENDING")).toBe("intervention");
  });
  it("requires intervention for paused, cancelled or stopped jobs", () => {
    for (const job of ["PAUSED", "CANCELLED", "COMPLETED"])
      for (const item of [
        "PENDING",
        "WAITING_DOWNLOAD",
        "UNCERTAIN",
        "VERIFYING",
      ])
        expect(workWaitingMode(item, job)).toBe("intervention");
    expect(workWaitingMode("BLOCKED", "PAUSED", false)).toBe("intervention");
    expect(workWaitingMode("BLOCKED", "CANCELLED", false)).toBe("intervention");
  });
  it("distinguishes active verification from uncertain operations awaiting review", () => {
    expect(workWaitingMode("UNCERTAIN", "RUNNING")).toBe("automatic");
    expect(workWaitingMode("VERIFYING", "QUEUED")).toBe("automatic");
    expect(workWaitingMode("UNCERTAIN", "RETRY_WAIT")).toBe("intervention");
    for (const item of ["FAILED", "BLOCKED_DOWNLOAD", "BLOCKED"])
      expect(workWaitingMode(item, "RUNNING")).toBe("intervention");
  });
  it("respects a verified automatic queue or an explicit manual dependency", () => {
    expect(workWaitingMode("BLOCKED", "COMPLETED", false)).toBe("automatic");
    expect(workWaitingMode("BLOCKED", "RUNNING", true)).toBe("intervention");
  });
  it("adds no waiting label to successful, skipped, cancelled or executing items", () => {
    for (const state of [
      "ACCEPTED",
      "SUCCEEDED",
      "SKIPPED",
      "CANCELLED",
      "READY",
      "EXCLUDED",
      "IN_FLIGHT",
      "SENDING",
      "RUNNING",
    ])
      expect(workWaitingMode(state, "RUNNING")).toBeNull();
  });
});
