import { useAuth } from "../auth/Auth";
import { useQuery, useQueryClient } from "@tanstack/react-query";
interface Session {
  search: { states: string[]; generation: number } | null;
  page: number;
  size: number;
  sort: string;
  registeredStatus: string | null;
}
const empty = (): Session => ({
  search: null,
  page: 0,
  size: 20,
  sort: "title,asc",
  registeredStatus: null,
});
// Session-only memory: shared across routes and cleared with the authentication cache.
export function useUpdateSession() {
  const cache = useQueryClient();
  const key = ["revision-updates-session", useAuth().user?.id];
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
    setView: (patch: Partial<Session>) =>
      cache.setQueryData<Session>(key, (previous) => ({
        ...(previous ?? empty()),
        ...patch,
      })),
  };
}
