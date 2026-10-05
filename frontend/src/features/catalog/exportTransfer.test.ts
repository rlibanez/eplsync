import { describe, expect, it, vi } from "vitest";
import { copyExportStream } from "./exportTransfer";

describe("export transfer", () => {
  it("writes chunks without constructing a blob", async () => {
    const response = new Response(new ReadableStream({ start(controller) {
      controller.enqueue(new Uint8Array([1, 2])); controller.enqueue(new Uint8Array([3])); controller.close();
    } }));
    const write = vi.fn().mockResolvedValue(undefined);
    await copyExportStream(response, { write }, "Empty");
    expect(write.mock.calls.map(([chunk]) => [...chunk])).toEqual([[1, 2], [3]]);
    expect(response.body?.locked).toBe(false);
  });
  it("cancels a download after a file write failure", async () => {
    const cancel = vi.fn();
    const response = new Response(new ReadableStream({ start(controller) {
      controller.enqueue(new Uint8Array([1]));
    }, cancel }));
    await expect(copyExportStream(response, { write: async () => { throw new Error("Disk full"); } }, "Empty"))
      .rejects.toThrow("Disk full");
    expect(cancel).toHaveBeenCalledOnce();
    expect(response.body?.locked).toBe(false);
  });
  it("rejects an empty export", async () => {
    await expect(copyExportStream(new Response(""), { write: async () => {} }, "No magnets"))
      .rejects.toThrow("No magnets");
  });
});
