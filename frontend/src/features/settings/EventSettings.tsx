import { TelegramSettings } from "../notifications/TelegramSettings";
import { NotificationSettings } from "../notifications/NotificationSettings";
import { useAuth } from "../auth/Auth";
import { ServerSettings } from "./ServerSettings";
import { EventMaintenance } from "../events/EventMaintenance";

export function EventSettings() {
  const auth = useAuth();
  return (
    <>
      <NotificationSettings />
      <TelegramSettings key={auth.user?.id} />
      {auth.can("SETTINGS_MANAGE") && <ServerSettings section="events" />}
      {auth.can("EVENTS_MANAGE") && <EventMaintenance />}
    </>
  );
}
