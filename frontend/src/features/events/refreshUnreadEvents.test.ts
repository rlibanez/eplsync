import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { expect, it } from "vitest";
import { refreshUnreadEvents } from "./refreshUnreadEvents";

for (const simultaneous of [1, 3]) {
  it(`replaces an initial pending count and ignores its late response (${simultaneous} refreshes)`, async () => {
    const cache = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    let resolveOld!: (value: { count: number; cursor: number }) => void;
    let initialSignal!: AbortSignal;
    let requests = 0;
    const key = ["event-unread", "alice", 3, "[]"];
    const observer = new QueryObserver(cache, {
      queryKey: key,
      queryFn: ({ signal }) => {
        if (++requests === 1) {
          initialSignal = signal;
          return new Promise<{ count: number; cursor: number }>((resolve) => {
            resolveOld = resolve;
          });
        }
        return Promise.resolve({ count: 1, cursor: 4 });
      },
    });
    const unsubscribe = observer.subscribe(() => {});
    try {
      expect(requests).toBe(1);
      await Promise.all(
        Array.from({ length: simultaneous }, () => refreshUnreadEvents(cache)),
      );
      expect(initialSignal.aborted).toBe(true);
      expect(requests).toBeGreaterThan(1);
      expect(cache.getQueryData(key)).toEqual({ count: 1, cursor: 4 });
      resolveOld({ count: 0, cursor: 3 });
      await Promise.resolve();
      await Promise.resolve();
      expect(cache.getQueryData(key)).toEqual({ count: 1, cursor: 4 });
    } finally {
      unsubscribe();
      cache.clear();
    }
  });
}
