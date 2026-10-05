import { useAuth } from "../auth/Auth";
import { ServerSettings } from "./ServerSettings";
import { EventMaintenance } from "../events/EventMaintenance";

export function EventSettings() {
  const auth = useAuth();
  return (
    <>
      {auth.can("SETTINGS_MANAGE") && <ServerSettings section="events" />}
      <EventMaintenance />
    </>
  );
}
