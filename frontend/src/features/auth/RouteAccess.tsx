import { type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { Alert } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useAuth, type Permission } from "./Auth";
export function RouteAccess({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const auth = useAuth();
  const { t } = useTranslation();
  let permission: Permission | undefined;
  if (pathname.startsWith("/catalog") || pathname.startsWith("/directory"))
    permission = "CATALOG_READ";
  else if (pathname.startsWith("/downloads/jobs"))
    permission = "TORRENT_JOBS_MANAGE";
  else if (pathname === "/downloads" && !auth.can("TORRENT_SYNC"))
    permission = "DOWNLOADS_READ";
  else if (pathname === "/events") permission = "EVENTS_MANAGE";
  else if (
    pathname === "/settings/database" &&
    !auth.can("CATALOG_DELETE") &&
    auth.user?.role !== "ADMIN"
  )
    permission = "CATALOG_IMPORT";
  else if (pathname === "/settings/covers") permission = "COVERS_MANAGE";
  else if (pathname === "/settings/torrent") permission = "SETTINGS_MANAGE";
  else if (pathname === "/settings/events") permission = "EVENTS_MANAGE";
  else if (pathname === "/settings/users" && auth.user?.role !== "ADMIN")
    return <Alert color="red">{t("auth.forbidden")}</Alert>;
  return permission && !auth.can(permission) ? (
    <Alert color="red">{t("auth.forbidden")}</Alert>
  ) : (
    <>{children}</>
  );
}
