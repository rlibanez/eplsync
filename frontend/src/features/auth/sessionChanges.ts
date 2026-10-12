import { resetCsrf } from "./transport";

const name = "eplsync-session-changed";
let channel: BroadcastChannel | undefined;

export function sessionRevision() {
  try {
    return localStorage.getItem(name) ?? "initial";
  } catch {
    return undefined;
  }
}

/** Notifications contain no account data, cookies, credentials or CSRF tokens. */
export function announceSessionChange() {
  resetCsrf();
  try {
    const previous = Number((localStorage.getItem(name) ?? "0").split("-")[0]);
    const next = Math.max(
      Date.now(),
      Number.isFinite(previous) ? previous + 1 : 0,
    );
    localStorage.setItem(name, `${next}-${Math.random()}`);
  } catch {
    // Focus/visibility checks still work when browser storage is unavailable.
  }
  channel?.postMessage(name);
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
    if (!channel && event.key === name && event.newValue !== null) receive();
  };
  window.addEventListener("storage", storage);
  return () => {
    channel?.close();
    channel = undefined;
    window.removeEventListener("storage", storage);
  };
}
