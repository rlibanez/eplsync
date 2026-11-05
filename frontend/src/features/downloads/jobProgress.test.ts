import { describe, it, expect } from "vitest";
import { jobPhaseProgress, downloadPhaseState, type Job } from "./shared";
const job = (values: Partial<Job>) =>
  ({
    selectedItems: 100,
    processedItems: 78,
    cancelled: 0,
    pending: 22,
    inFlight: 0,
    status: "RUNNING",
    ...values,
  }) as Job;
describe("independent job phases", () => {
  it("keeps submission, downloads and Calibre independent", () => {
    expect(
      jobPhaseProgress(
        job({
          download: {
            selected: 50,
            downloaded: 6,
            pending: 44,
            attention: 0,
            ignored: 0,
          },
          calibre: {
            counts: {
              total: 100,
              sent: 1,
              existing: 1,
              failed: 1,
              pending: 97,
              uncertain: 0,
              cancelled: 0,
              omitted: 0,
            },
            jobs: [],
          },
        }),
      ),
    ).toEqual({ submission: 78, downloads: 6, calibre: 3 });
  });
  it("does not turn omissions or errors into downloaded files", () => {
    const value = job({
      download: {
        selected: 10,
        downloaded: 3,
        pending: 0,
        attention: 2,
        ignored: 5,
      },
    });
    expect(jobPhaseProgress(value).downloads).toBe(3);
    expect(downloadPhaseState(value)).toBe("attention");
  });
  it("uses the fixed selection even when most torrents are not yet submitted", () => {
    const value = job({
      selectedItems: 66047,
      processedItems: 1183,
      download: {
        selected: 1183,
        downloaded: 310,
        pending: 873,
        attention: 0,
        ignored: 0,
      },
    });
    expect(jobPhaseProgress(value)).toEqual({
      submission: 1,
      downloads: 0,
      calibre: null,
    });
    value.download!.selected = 2000;
    expect(jobPhaseProgress(value).downloads).toBe(0);
  });
  it("handles missing phases, empty totals and cancellation", () => {
    expect(
      jobPhaseProgress(job({ selectedItems: 0, processedItems: 0 })),
    ).toEqual({ submission: 0, downloads: null, calibre: null });
    expect(
      jobPhaseProgress(job({ processedItems: 60, cancelled: 40 })).submission,
    ).toBe(100);
    expect(downloadPhaseState(job({ status: "CANCELLED" }))).toBe("CANCELLED");
  });
  it("finishes download tracking independently of unchecked inventory", () => {
    const value = job({
      pending: 0,
      processedItems: 100,
      download: {
        selected: 100,
        downloaded: 100,
        pending: 0,
        attention: 0,
        ignored: 0,
        filesChecked: 0,
      },
    });
    expect(downloadPhaseState(value)).toBe("COMPLETED");
    expect(jobPhaseProgress(value).downloads).toBe(100);
  });
});
