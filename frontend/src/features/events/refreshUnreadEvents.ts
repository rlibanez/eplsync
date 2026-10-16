import type { QueryClient } from "@tanstack/react-query";

export async function refreshUnreadEvents(cache: QueryClient): Promise<void> {
  const queryKey = ["event-unread"];
  // Invalidation alone reuses an initial in-flight request with no cached data.
  // Cancel it first, so a live event always starts a fresh count request.
  await cache.cancelQueries({ queryKey });
  await cache.invalidateQueries({ queryKey });
}
