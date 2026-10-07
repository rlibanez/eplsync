import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { SyncResult } from "./SyncReport";
const key = ["torrent-sync-session"];
export const defaultSyncView = {
  tab: "books",
  action: "CHANGED",
  outcome: "ALL",
  search: "",
  page: 0,
  size: 20,
  bookSort: "eplId,asc",
  ignoredSort: "name,asc",
};
interface Session {
  report: SyncResult | null;
  view: typeof defaultSyncView;
}
const empty = (): Session => ({ report: null, view: { ...defaultSyncView } });
// Keep the last snapshot in memory across routes; never refetch a sync automatically.
export function useSyncSession() {
  const cache = useQueryClient();
  const { data } = useQuery<Session>({
    queryKey: key,
    queryFn: empty,
    enabled: false,
    initialData: empty,
    gcTime: Infinity,
    staleTime: Infinity,
  });
  return {
    ...data,
    save: (report: SyncResult) =>
      cache.setQueryData<Session>(key, {
        report,
        view: { ...defaultSyncView },
      }),
    markLinked: (hash: string) =>
      cache.setQueryData<Session>(key, (previous) =>
        previous?.report
          ? {
              ...previous,
              report: {
                ...previous.report,
                ignoredTorrents: previous.report.ignoredTorrents.map((item) =>
                  item.hash === hash ? { ...item, linked: true } : item,
                ),
              },
            }
          : previous,
      ),
    close: () => cache.setQueryData<Session>(key, empty()),
    setView: (patch: Partial<typeof defaultSyncView>) =>
      cache.setQueryData<Session>(key, (previous) => ({
        ...(previous || empty()),
        view: { ...(previous?.view || defaultSyncView), ...patch },
      })),
  };
}
