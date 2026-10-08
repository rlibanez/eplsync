import { resetCsrf } from "./transport";

const name = "eplsync-session-changed";
let channel: BroadcastChannel | undefined;

/** Notifications contain no account data, cookies, credentials or CSRF tokens. */
export function announceSessionChange() {
  resetCsrf();
  if (channel) channel.postMessage(name);
  else {
    try {
      localStorage.setItem(name, `${Date.now()}-${Math.random()}`);
    } catch {
      // Focus/visibility checks still work when browser storage is unavailable.
    }
  }
}
export function subscribeSessionChanges(changed: () => void) {
  const receive = () => {
    resetCsrf();
    changed();
  };
  if (typeof BroadcastChannel !== "undefined") {
    channel = new BroadcastChannel(name);
    channel.onmessage = (event) => {
      if (event.data === name) receive();
    };
  }
  const storage = (event: StorageEvent) => {
    if (event.key === name && event.newValue !== null) receive();
  };
  window.addEventListener("storage", storage);
  return () => {
    channel?.close();
    channel = undefined;
    window.removeEventListener("storage", storage);
  };
}
