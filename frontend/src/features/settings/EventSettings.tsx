import { ServerSettings } from "./ServerSettings";
import { EventMaintenance } from "../events/EventMaintenance";

export function EventSettings() {
  return <><ServerSettings section="events" /><EventMaintenance /></>;
}
