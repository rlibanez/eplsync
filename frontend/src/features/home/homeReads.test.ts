import { describe, it, expect } from "vitest";
import { homeReads } from "./homeReads";

describe("Home read coordination", () => {
  it("continues after a failed request", async () => {
    await expect(
      homeReads.run(async () => {
        throw new Error("network");
      }, new AbortController().signal),
    ).rejects.toThrow("network");
    expect(
      await homeReads.run(async () => 42, new AbortController().signal),
    ).toBe(42);
  });
  it("serializes future sections and skips cancelled reads without blocking others", async () => {
    const started: string[] = [];
    let release!: () => void;
    const first = homeReads.run(async () => {
      started.push("first");
      await new Promise<void>((resolve) => {
        release = resolve;
      });
    }, new AbortController().signal);
    const cancelled = new AbortController();
    const second = homeReads.run(async () => {
      started.push("cancelled");
    }, cancelled.signal);
    const rejected = expect(second).rejects.toMatchObject({
      name: "AbortError",
    });
    const third = homeReads.run(async () => {
      started.push("third");
      return 3;
    }, new AbortController().signal);
    cancelled.abort();
    await Promise.resolve();
    expect(started).toEqual(["first"]);
    release();
    await first;
    await rejected;
    expect(await third).toBe(3);
    expect(started).toEqual(["first", "third"]);
  });
});
