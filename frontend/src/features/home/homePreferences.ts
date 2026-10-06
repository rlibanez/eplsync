import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { get } from "../../api/catalog";
import { authRequest } from "../auth/transport";
export type HomeSectionId =
  | "header"
  | "overview"
  | "newReleases"
  | "recentUpdates"
  | "recentBooks"
  | "recentEvents";
export interface HomeSection {
  id: HomeSectionId;
  enabled: boolean;
  bookCount: number | null;
  eventCount: number | null;
}
export interface HomePreferences {
  sections: HomeSection[];
}
export const defaultHomePreferences = (): HomePreferences => ({
  sections: [
    { id: "header", enabled: true, bookCount: null, eventCount: null },
    { id: "overview", enabled: true, bookCount: null, eventCount: null },
    { id: "newReleases", enabled: true, bookCount: 10, eventCount: null },
    { id: "recentUpdates", enabled: true, bookCount: 10, eventCount: null },
    { id: "recentBooks", enabled: true, bookCount: 10, eventCount: null },
    { id: "recentEvents", enabled: true, bookCount: null, eventCount: 10 },
  ],
});
export const sectionLabel = (id: HomeSectionId) =>
  id === "header" || id === "overview" ? `homeSettings.${id}` : `home.${id}`;
export function useHomePreferences() {
  return useQuery({
    queryKey: ["home-preferences"],
    queryFn: ({ signal }) => get<HomePreferences>("/auth/home", signal),
  });
}
export function useSaveHomePreferences() {
  const cache = useQueryClient();
  return useMutation({
    mutationFn: (preferences: HomePreferences) =>
      authRequest<HomePreferences>("/auth/home", "PUT", preferences),
    onSuccess: (preferences) => {
      cache.setQueryData(["home-preferences"], preferences);
    },
  });
}
