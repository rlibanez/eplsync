import { useAuth } from "../auth/Auth";
import { NavLink, Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";
export function SettingsLayout() {
  const { t } = useTranslation();
  const auth = useAuth();
  return (
    <>
      <h1>{t("nav.settings")}</h1>
      <nav className="section-tabs" aria-label={t("nav.settings")}>
        {[
          "general",
          "account",
          "database",
          "events",
          "covers",
          "torrent",
          "users",
          "about",
        ]
          .filter((key) => {
            if (key === "users") return auth.user?.role === "ADMIN";
            if (key === "database")
              return (
                auth.can("CATALOG_IMPORT") ||
                auth.can("CATALOG_DELETE") ||
                auth.user?.role === "ADMIN"
              );
            if (key === "events") return auth.can("EVENTS_MANAGE");
            if (key === "covers") return auth.can("COVERS_MANAGE");
            if (key === "torrent") return auth.can("SETTINGS_MANAGE");
            return true;
          })
          .map((key) => (
            <NavLink key={key} to={`/settings/${key}`}>
              {t(
                key === "account"
                  ? "auth.account"
                  : key === "users"
                    ? "auth.users"
                    : `settings.${key}`,
              )}
            </NavLink>
          ))}
      </nav>
      <Outlet />
    </>
  );
}
