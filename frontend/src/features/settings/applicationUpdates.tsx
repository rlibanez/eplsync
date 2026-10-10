import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";
import { useNotifications } from "../notifications/Notifications";

export interface ApplicationUpdateStatus {
  automatic: boolean;
  state:
    | "NOT_CHECKED"
    | "AVAILABLE"
    | "UP_TO_DATE"
    | "UNKNOWN_VERSION"
    | "NO_RELEASE"
    | "UNAVAILABLE";
  latestVersion: string | null;
  releaseUrl: string | null;
  checkedAt: string | null;
  checking: boolean;
}
export function useApplicationUpdates() {
  const auth = useAuth();
  return useQuery({
    queryKey: ["application-updates", auth.user?.id],
    queryFn: ({ signal }) =>
      get<ApplicationUpdateStatus>("/application/updates", signal),
    enabled: auth.user?.role === "ADMIN",
    staleTime: 30000,
    refetchInterval: (query) => (query.state.data?.checking ? 1000 : 60000),
    retry: false,
  });
}
export function ApplicationUpdateNotice() {
  const auth = useAuth();
  const result = useApplicationUpdates();
  const { notify } = useNotifications();
  const { t } = useTranslation();
  useEffect(() => {
    const status = result.data;
    if (
      auth.user?.role !== "ADMIN" ||
      status?.state !== "AVAILABLE" ||
      !status.latestVersion
    )
      return;
    const key = `eplsync.update-notice.${auth.user.id}.${status.latestVersion}`;
    if (localStorage.getItem(key)) return;
    localStorage.setItem(key, "shown");
    notify({
      title: t("applicationVersion.updateTitle"),
      message: t("applicationVersion.available", {
        version: status.latestVersion,
      }),
      tone: "info",
      href: "/settings/about",
    });
  }, [auth.user, result.data, notify, t]);
  return null;
}
