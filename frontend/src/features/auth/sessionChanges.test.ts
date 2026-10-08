import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  announceSessionChange,
  subscribeSessionChanges,
} from "./sessionChanges";
import { resetCsrf } from "./transport";
vi.mock("./transport", () => ({ resetCsrf: vi.fn() }));
let unsubscribe: (() => void) | undefined;
beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal("window", new EventTarget());
});
afterEach(() => {
  unsubscribe?.();
  unsubscribe = undefined;
  vi.unstubAllGlobals();
});
it("broadcasts only a signal and receives remote changes without rebroadcasting", () => {
  const postMessage = vi.fn();
  const close = vi.fn();
  let instance: { onmessage?: (event: { data: string }) => void };
  vi.stubGlobal(
    "BroadcastChannel",
    class {
      onmessage?: (event: { data: string }) => void;
      postMessage = postMessage;
      close = close;
      constructor() {
        instance = this;
      }
    },
  );
  const changed = vi.fn();
  unsubscribe = subscribeSessionChanges(changed);
  announceSessionChange();
  expect(postMessage).toHaveBeenCalledExactlyOnceWith(
    "eplsync-session-changed",
  );
  instance!.onmessage!({ data: "unrelated" });
  expect(changed).not.toHaveBeenCalled();
  instance!.onmessage!({ data: "eplsync-session-changed" });
  expect(changed).toHaveBeenCalledTimes(1);
  expect(resetCsrf).toHaveBeenCalledTimes(2);
  expect(postMessage).toHaveBeenCalledTimes(1);
});
it("uses storage without BroadcastChannel and removes its listener", () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  const setItem = vi.fn();
  vi.stubGlobal("localStorage", { setItem });
  const changed = vi.fn();
  unsubscribe = subscribeSessionChanges(changed);
  announceSessionChange();
  expect(setItem.mock.calls[0][0]).toBe("eplsync-session-changed");
  const event = () =>
    Object.assign(new Event("storage"), {
      key: "eplsync-session-changed",
      newValue: "nonce",
    });
  window.dispatchEvent(event());
  expect(changed).toHaveBeenCalledTimes(1);
  unsubscribe();
  unsubscribe = undefined;
  window.dispatchEvent(event());
  expect(changed).toHaveBeenCalledTimes(1);
});
it("tolerates unavailable browser storage", () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  vi.stubGlobal("localStorage", {
    setItem: () => {
      throw new Error("blocked");
    },
  });
  expect(announceSessionChange).not.toThrow();
});
