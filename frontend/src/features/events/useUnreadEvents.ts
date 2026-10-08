import { useAuth } from "../auth/Auth";
import { useEffect, useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import { post } from "../downloads/shared";

import {
  eventReadCursor,
  eventReadItemsSnapshot,
  readItems,
  resetEventReadState,
} from "./eventReadState";
export { markEventsRead, markOperationRead } from "./eventReadState";

const changed = "eplsync-events-read";
function subscribe(listener: () => void) {
  window.addEventListener(changed, listener);
  window.addEventListener("storage", listener);
  return () => {
    window.removeEventListener(changed, listener);
    window.removeEventListener("storage", listener);
  };
}
export function useEventReadCursor() {
  const accountId = useAuth().user?.id;
  return useSyncExternalStore(
    subscribe,
    () => eventReadCursor(accountId),
    () => 0,
  );
}
export function useUnreadEvents() {
  const auth = useAuth();
  const accountId = auth.user?.id;
  const read = useEventReadCursor();
  const individual = useSyncExternalStore(
    subscribe,
    () => eventReadItemsSnapshot(accountId),
    () => "[]",
  );
  const result = useQuery({
    queryKey: ["event-unread", accountId, read, individual],
    enabled: auth.can("EVENTS_MANAGE"),
    queryFn: () =>
      post<{ count: number; cursor: number }>("/events/unread", {
        afterId: read,
        readIds: readItems(individual),
      }),
  });
  useEffect(() => {
    // A replaced/restored database can have a lower sequence than this browser.
    if (
      !result.isFetching &&
      result.data &&
      result.data.cursor < Math.max(read, ...readItems(individual))
    ) {
      resetEventReadState(accountId);
    }
  }, [result.data, result.isFetching, read, individual, accountId]);
  return result.data?.count ?? 0;
}
