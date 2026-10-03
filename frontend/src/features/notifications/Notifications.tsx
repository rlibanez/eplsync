import {
  allowsNotification,
  readNotificationPreferences,
} from "./NotificationSettings";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Notification, Portal, Text } from "@mantine/core";
import { Check, CircleAlert, Info } from "lucide-react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";

type Tone = "success" | "error" | "warning" | "info";
export interface Notice {
  id: string;
  title: string;
  message: string;
  tone: Tone;
  href?: string;
  durationMs?: number;
  phase?: "start" | "result";
}
type Input = Omit<Notice, "id"> & { id?: string };
const Context = createContext<{ notify: (notice: Input) => void } | null>(null);
const colors = {
  success: "green",
  error: "red",
  warning: "orange",
  info: "blue",
};
const icons = {
  success: Check,
  error: CircleAlert,
  warning: CircleAlert,
  info: Info,
};

function Toast({ notice, dismiss }: { notice: Notice; dismiss: () => void }) {
  const { t } = useTranslation();
  const [paused, setPaused] = useState(false);
  const dismissRef = useRef(dismiss);
  dismissRef.current = dismiss;
  useEffect(() => {
    if (paused) return;
    const timer = window.setTimeout(
      () => dismissRef.current(),
      notice.durationMs ?? 6000,
    );
    return () => window.clearTimeout(timer);
  }, [paused, notice.durationMs]);
  const Icon = icons[notice.tone];
  return (
    <div
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget)) setPaused(false);
      }}
    >
      <Notification
        title={notice.title}
        color={colors[notice.tone]}
        icon={<Icon size={18} />}
        onClose={dismiss}
        closeButtonProps={{ "aria-label": t("notifications.dismiss") }}
        role={notice.tone === "error" ? "alert" : "status"}
      >
        <Text
          size="sm"
          style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
        >
          {notice.message}
        </Text>
        {notice.href && <Link to={notice.href}>{t("notifications.view")}</Link>}
      </Notification>
    </div>
  );
}
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [toasts, setToasts] = useState<Notice[]>([]);
  const seen = useRef(new Set<string>());
  const notify = useCallback((input: Input) => {
    const id =
      input.id ||
      globalThis.crypto?.randomUUID?.() ||
      `notice:${Date.now()}:${Math.random().toString(36).slice(2)}`;
    if (seen.current.has(id)) return;
    seen.current.add(id);
    if (seen.current.size > 1000)
      seen.current.delete(seen.current.values().next().value!);
    const preferences = readNotificationPreferences();
    if (!allowsNotification(preferences, input.tone, input.phase)) return;
    const notice = { ...input, id, durationMs: preferences.seconds * 1000 };
    setToasts((current) => [...current, notice].slice(-3));
  }, []);
  useEffect(() => {
    // Old browser-only history is intentionally retired; local errors are transient.
    try {
      localStorage.removeItem("eplsync.notifications.v1");
      localStorage.removeItem("eplsync.lastNotifiedCoverTask");
    } catch {
      /* Storage may be disabled. */
    }
  }, []);
  // Observe completion centrally, including operations whose initiating view has unmounted.
  useEffect(
    () =>
      cache.getMutationCache().subscribe((event) => {
        if (
          event.type !== "updated" ||
          !["success", "error"].includes(event.action.type)
        )
          return;
        const mutation = event.mutation;
        const meta = mutation.options.meta?.notice as
          | { title?: string; success?: string; error?: string; href?: string }
          | undefined;
        if (!meta) return;
        const failed = event.action.type === "error";
        const kind = mutation.options.mutationKey?.[0];
        const backendOwned = mutation.options.meta?.backendEvents === true;
        if (
          backendOwned &&
          failed &&
          (mutation.state.error as { eventOperationId?: string })
            ?.eventOperationId
        )
          return;
        if ((backendOwned || mutation.options.meta?.silentSuccess) && !failed)
          return;
        if (kind === "torrent-sync" && mutation.state.variables === false) {
          // A transport failure is local; authoritative outcomes arrive over SSE.
          if (
            !failed ||
            (mutation.state.error as { eventOperationId?: string })
              ?.eventOperationId
          )
            return;
        }
        const data = mutation.state.data as Record<string, unknown> | undefined;
        let message = t(
          failed
            ? meta.error || "downloads.uncertain"
            : meta.success || "notifications.operationDone",
        );
        let tone: Tone = failed ? "error" : "success";
        let href = meta.href;
        if (failed) {
          const error = mutation.state.error as Error & {
            status?: number;
            details?: string;
          };
          if (!meta.error && error?.status)
            message = t("downloads.httpError", { status: error.status });
          if (mutation.options.mutationKey?.[0] === "connection-check")
            message = t(
              error?.status === 504
                ? "torrent.timeout"
                : error?.status === 502
                  ? "torrent.upstream"
                  : error?.status === 503
                    ? "torrent.interrupted"
                    : error?.status
                      ? "torrent.httpError"
                      : "torrent.network",
              { status: error?.status },
            );
          if (error?.details) message = error.details;
        } else {
          const kind = mutation.options.mutationKey?.[0];
          if (kind === "cover-check") {
            const item = (
              data?.items as { available: boolean | null }[] | undefined
            )?.[0];
            if (item?.available !== false) return;
            message = t("covers.repaired");
          }
          if (kind === "send-books") {
            if (data?.jobId) {
              return; // Job lifecycle events are emitted by the backend.
            } else if (meta.success) {
              message =
                t(`statuses.${data?.status}`) +
                " · EPL " +
                data?.eplId +
                "\n" +
                t(meta.success);
            } else
              message = t(
                data?.status === "ALREADY_EXISTS"
                  ? "detail.alreadySent"
                  : "detail.sent",
              );
          }
          if (kind === "torrent-sync")
            message = t(
              data?.dryRun ? "syncReport.preview" : "syncReport.applied",
            );
          if (kind === "connection-check") {
            message = t(
              !data?.enabled
                ? "torrent.disabled"
                : data.connected
                  ? "torrent.connected"
                  : "torrent.disconnected",
            );
            tone = !data?.enabled
              ? "warning"
              : data.connected
                ? "success"
                : "error";
          }
        }
        notify({
          id: `mutation:${mutation.mutationId}:${mutation.state.submittedAt}:${event.action.type}`,
          title: t(meta.title || "notifications.title"),
          message,
          tone,
          href,
        });
      }),
    [cache, notify, t],
  );
  return (
    <Context.Provider value={{ notify }}>
      {children}
      <Portal>
        <aside
          className="notification-toasts"
          aria-label={t("notifications.floating")}
        >
          {toasts.map((notice) => (
            <Toast
              key={notice.id}
              notice={notice}
              dismiss={() =>
                setToasts((current) =>
                  current.filter((n) => n.id !== notice.id),
                )
              }
            />
          ))}
        </aside>
      </Portal>
    </Context.Provider>
  );
}
export function useNotifications() {
  const context = useContext(Context);
  if (!context) throw new Error("NotificationsProvider required");
  return context;
}
