import { useQuery } from "@tanstack/react-query";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";

import {
  parseNotificationPreferences,
  type NotificationPreferences,
} from "./notificationPreferences";
export type EventDisplayPreferences = NotificationPreferences;
export function useEventDisplayPreferences() {
  const { user } = useAuth();
  return useQuery({
    queryKey: ["event-display-preferences", user?.id],
    queryFn: async ({ signal }) =>
      parseNotificationPreferences(
        JSON.stringify(
          await get<EventDisplayPreferences>("/auth/notifications", signal),
        ),
      ),
    enabled: !!user,
    refetchInterval: 30000,
  });
}
