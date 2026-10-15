import { NotificationSettings } from "../notifications/NotificationSettings";
import { useAuth } from "../auth/Auth";
import { ServerSettings } from "./ServerSettings";
import { EventMaintenance } from "../events/EventMaintenance";

export function EventSettings() {
  const auth = useAuth();
  return (
    <>
      <NotificationSettings />
      {auth.can("SETTINGS_MANAGE") && <ServerSettings section="events" />}
      {auth.can("EVENTS_MANAGE") && <EventMaintenance />}
    </>
  );
}
