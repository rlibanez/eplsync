// This worker owns the sole SSE connection; tabs only exchange local messages.
// It deliberately receives no cookies, passwords or CSRF tokens from the tabs.
export {};
const scope = self as unknown as {
  onconnect: (event: MessageEvent) => void;
};
const ports = new Set<MessagePort>();
let source: EventSource | undefined;
let session: string | undefined;
let generation = -1;
let account: string | undefined;
let state: { type: string; data: string } | undefined;
const types = ["ready", "reset", "database-reset", "refresh", "event"];
function closeSource() {
  source?.close();
  source = undefined;
  state = undefined;
}
function broadcast(message: { type: string; data: string }) {
  for (const port of ports) port.postMessage(message);
}
function connect() {
  if (source || !ports.size) return;
  const connection = new EventSource("/api/events/stream");
  source = connection;
  for (const type of types)
    connection.addEventListener(type, (event) => {
      if (source !== connection) return;
      const message = { type, data: (event as MessageEvent).data };
      if (type === "ready" || type === "reset")
        state = { ...message, type: "ready" };
      if (type === "event") {
        try {
          state = {
            type: "ready",
            data: JSON.stringify({ cursor: JSON.parse(message.data).id }),
          };
        } catch {
          return;
        }
      }
      broadcast(message);
    });
  connection.onerror = () => {
    if (source !== connection) return;
    state = { type: "error", data: "" };
    broadcast(state);
  };
}
scope.onconnect = ({ ports: [port] }) => {
  port.onmessage = ({ data }) => {
    if (
      data?.type === "subscribe" &&
      typeof data.session === "string" &&
      typeof data.account === "string"
    ) {
      const next =
        data.session === "initial" ? 0 : Number(data.session.split("-")[0]);
      if (!Number.isFinite(next) || next < generation) {
        port.postMessage({ type: "stale" });
        return;
      }
      if (data.session !== session || data.account !== account) {
        closeSource();
        for (const previous of ports) previous.postMessage({ type: "stale" });
        ports.clear();
        session = data.session;
        generation = next;
        account = data.account;
      }
      ports.add(port);
      port.postMessage({ type: "attached" });
      if (state) port.postMessage(state);
      connect();
    } else if (data?.type === "unsubscribe") {
      ports.delete(port);
      port.close();
      if (!ports.size) closeSource();
    }
  };
  port.start();
};
