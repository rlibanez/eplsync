import { sessionRevision } from "../auth/sessionChanges";

export type StreamMessage = {
  type: "ready" | "reset" | "database-reset" | "refresh" | "event" | "error";
  data: string;
};
const types = ["ready", "reset", "database-reset", "refresh", "event"] as const;

/** Prefer a SharedWorker (also over LAN HTTP); retain locks and polling as fallbacks. */
export function subscribeEventStream(
  account: string,
  receive: (message: StreamMessage) => void,
) {
  const revision = sessionRevision();
  const current = () => revision === sessionRevision();
  let stopped = false;
  let source: EventSource | undefined;
  let release: (() => void) | undefined;
  let pending: AbortController | undefined;
  let state: StreamMessage | undefined;
  let bus: BroadcastChannel | undefined;
  let poll: number | undefined;
  let worker: SharedWorker | undefined;
  let workerFailed = false;
  let attached = false;
  let workerTimeout: number | undefined;
  const detachWorker = () => {
    window.clearTimeout(workerTimeout);
    workerTimeout = undefined;
    if (worker) {
      worker.port.postMessage({ type: "unsubscribe" });
      worker.port.close();
      worker.onerror = null;
      worker = undefined;
    }
    attached = false;
  };
  const deliver = (message: StreamMessage) => {
    if (!stopped && current()) receive(message);
  };
  const emit = (message: StreamMessage) => {
    if (!current()) return;
    if (["ready", "reset", "error"].includes(message.type))
      state =
        message.type === "reset" ? { ...message, type: "ready" } : message;
    // Keep the handshake cursor current for a newly opened tab.
    if (message.type === "event" && state && state.type !== "error") {
      state = {
        type: "ready",
        data: JSON.stringify({ cursor: JSON.parse(message.data).id }),
      };
    }
    bus?.postMessage(message);
    deliver(message);
  };
  const suspend = () => {
    detachWorker();
    pending?.abort();
    source?.close();
    source = undefined;
    state = undefined;
    release?.();
    release = undefined;
  };
  const startLock = () => {
    if (
      stopped ||
      !current() ||
      !bus ||
      poll !== undefined ||
      (pending && !pending.signal.aborted)
    )
      return;
    pending = new AbortController();
    const attempt = pending;
    void navigator.locks
      .request("eplsync-events-owner", { signal: attempt.signal }, async () => {
        if (stopped || !current() || attempt.signal.aborted) return;
        await new Promise<void>((resolve) => {
          release = resolve;
          source = new EventSource("/api/events/stream");
          for (const type of types)
            source.addEventListener(type, (event) => {
              emit({ type, data: (event as MessageEvent).data });
            });
          source.onerror = () => emit({ type: "error", data: "" });
        });
      })
      .catch((error) => {
        if (error.name !== "AbortError" && !stopped) fallback();
      });
    bus?.postMessage({ type: "hello" });
  };
  const fallback = () => {
    suspend();
    if (poll !== undefined) return;
    // Older/insecure browsers must not open one permanent connection per tab.
    deliver({ type: "refresh", data: "" });
    poll = window.setInterval(
      () => deliver({ type: "refresh", data: "" }),
      30_000,
    );
  };
  const startAlternative = () => {
    if (stopped || !current() || poll !== undefined) return;
    if (
      revision !== undefined &&
      typeof BroadcastChannel !== "undefined" &&
      navigator.locks
    ) {
      try {
        if (!bus) {
          bus = new BroadcastChannel(`eplsync-events:${account}:${revision}`);
          bus.onmessage = ({ data }) => {
            if (!current()) return;
            if (data?.type === "hello") {
              if (source && state) bus?.postMessage(state);
            } else if (
              data &&
              [...types, "error"].includes(data.type) &&
              typeof data.data === "string"
            )
              deliver(data);
          };
        }
        startLock();
      } catch {
        fallback();
      }
    } else fallback();
  };
  const start = () => {
    if (stopped || !current() || worker || poll !== undefined) return;
    if (
      !workerFailed &&
      revision !== undefined &&
      typeof SharedWorker !== "undefined"
    ) {
      try {
        const connection = new SharedWorker(
          new URL("./eventStream.worker.ts", import.meta.url),
          {
            type: "module",
            name: "eplsync-events-v1",
          },
        );
        worker = connection;
        const failed = () => {
          if (worker !== connection || stopped) return;
          workerFailed = true;
          detachWorker();
          startAlternative();
        };
        connection.onerror = failed;
        connection.port.onmessage = ({ data }) => {
          if (worker !== connection || !current() || stopped) return;
          if (data?.type === "attached") {
            attached = true;
            window.clearTimeout(workerTimeout);
          } else if (data?.type === "stale") {
            detachWorker();
            window.dispatchEvent(new Event("eplsync-auth-session-changed"));
          } else if (
            data &&
            [...types, "error"].includes(data.type) &&
            typeof data.data === "string"
          )
            deliver(data);
        };
        connection.port.start();
        connection.port.postMessage({
          type: "subscribe",
          account,
          session: revision,
        });
        workerTimeout = window.setTimeout(() => {
          if (!attached) failed();
        }, 5000);
        return;
      } catch {
        workerFailed = true;
        detachWorker();
      }
    }
    startAlternative();
  };
  start();
  // A frozen document cannot distribute events; release ownership before freezing.
  document.addEventListener("freeze", suspend);
  document.addEventListener("resume", start);
  window.addEventListener("pagehide", suspend);
  window.addEventListener("pageshow", start);
  return () => {
    stopped = true;
    suspend();
    bus?.close();
    window.clearInterval(poll);
    document.removeEventListener("freeze", suspend);
    document.removeEventListener("resume", start);
    window.removeEventListener("pagehide", suspend);
    window.removeEventListener("pageshow", start);
  };
}
