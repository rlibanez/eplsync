import { describe, it, expect, vi } from "vitest";
import {
  AppearanceSession,
  type Appearance,
  type StoredAppearance,
  type AppearanceRequest,
} from "./appearanceSession";
const local: Appearance = { scheme: "dark", palette: "teal" };
const stored: StoredAppearance = {
  userId: "a",
  scheme: "light",
  palette: "blue",
};
const settle = async () => {
  for (let i = 0; i < 12; i++) await Promise.resolve();
};
describe("personal appearance", () => {
  it("loads server preferences instead of the previous browser value without writing", async () => {
    const request = vi.fn().mockResolvedValue(stored),
      apply = vi.fn();
    await new AppearanceSession("a", local, request, apply, vi.fn()).load();
    expect(apply).toHaveBeenLastCalledWith({
      scheme: "light",
      palette: "blue",
    });
    expect(request).toHaveBeenCalledTimes(1);
  });
  it("adopts local preferences once without overwriting a concurrently initialized account", async () => {
    const request = vi
        .fn()
        .mockResolvedValueOnce({ userId: "a", scheme: null, palette: null })
        .mockResolvedValueOnce(stored),
      apply = vi.fn();
    await new AppearanceSession("a", local, request, apply, vi.fn()).load();
    await settle();
    expect(request.mock.calls[1][1]).toEqual({
      userId: "a",
      ...local,
      initialize: true,
    });
    expect(apply).toHaveBeenLastCalledWith({
      scheme: "light",
      palette: "blue",
    });
  });
  it("merges changes made while loading with the account preferences", async () => {
    let resolve!: (v: StoredAppearance) => void;
    const request = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((r) => {
            resolve = r;
          }),
      )
      .mockResolvedValue({ ...stored, palette: "rose" });
    const apply = vi.fn(),
      session = new AppearanceSession("a", local, request, apply, vi.fn());
    void session.load();
    session.set({ palette: "rose" });
    resolve(stored);
    await settle();
    expect(request.mock.calls[1][1]).toEqual({
      userId: "a",
      scheme: "light",
      palette: "rose",
      initialize: false,
    });
  });
  it("serializes rapid edits and keeps the last choice", async () => {
    let resolve!: (v: StoredAppearance) => void;
    const request = vi
      .fn()
      .mockResolvedValueOnce(stored)
      .mockImplementationOnce(
        () =>
          new Promise((r) => {
            resolve = r;
          }),
      )
      .mockResolvedValue({ ...stored, scheme: "dark", palette: "rose" });
    const apply = vi.fn(),
      session = new AppearanceSession("a", local, request, apply, vi.fn());
    await session.load();
    session.set({ scheme: "dark" });
    session.set({ palette: "rose" });
    expect(request).toHaveBeenCalledTimes(2);
    resolve({ ...stored, scheme: "dark" });
    await settle();
    expect(request.mock.calls[2][1]).toMatchObject({
      scheme: "dark",
      palette: "rose",
    });
    expect(apply).toHaveBeenLastCalledWith({ scheme: "dark", palette: "rose" });
  });
  it("ignores late responses after logout or an account switch", async () => {
    let resolve!: (v: StoredAppearance) => void;
    const request = vi.fn<AppearanceRequest>(
        () =>
          new Promise<StoredAppearance>((r) => {
            resolve = r;
          }),
      ),
      apply = vi.fn();
    const session = new AppearanceSession("a", local, request, apply, vi.fn());
    void session.load();
    session.dispose();
    resolve(stored);
    await settle();
    expect(apply).not.toHaveBeenCalled();
    expect(request).toHaveBeenCalledTimes(1);
    expect(request.mock.calls[0][2].aborted).toBe(true);
  });
  it("does not apply another account's response", async () => {
    const apply = vi.fn(),
      error = vi.fn();
    await new AppearanceSession(
      "a",
      local,
      vi.fn().mockResolvedValue({ ...stored, userId: "b" }),
      apply,
      error,
    ).load();
    expect(apply).not.toHaveBeenCalled();
    expect(error).toHaveBeenLastCalledWith(true);
  });
  it("keeps local edits on failure and retries when the user changes appearance", async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce(stored)
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue({ ...stored, scheme: "dark", palette: "rose" });
    const error = vi.fn(),
      apply = vi.fn(),
      session = new AppearanceSession("a", local, request, apply, error);
    await session.load();
    session.set({ scheme: "dark" });
    await settle();
    expect(error).toHaveBeenLastCalledWith(true);
    session.set({ palette: "rose" });
    await settle();
    expect(error).toHaveBeenLastCalledWith(false);
    expect(apply).toHaveBeenLastCalledWith({ scheme: "dark", palette: "rose" });
  });
});
