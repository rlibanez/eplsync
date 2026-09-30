import { NavLink, Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";
export function SettingsLayout() {
  const { t } = useTranslation();
  return (
    <>
      <h1>{t("nav.settings")}</h1>
      <nav className="section-tabs" aria-label={t("nav.settings")}>
        {["general", "database", "covers", "torrent"].map((key) => (
          <NavLink key={key} to={`/settings/${key}`}>
            {t(`settings.${key}`)}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </>
  );
}
