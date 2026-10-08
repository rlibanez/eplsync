import { useAuth } from "../features/auth/Auth";
import { RouteAccess } from "../features/auth/RouteAccess";
import { useUnreadEvents } from "../features/events/useUnreadEvents";
import { EventConnection } from "../features/events/EventConnection";
import {
  NotificationsProvider,
  useNotifications,
} from "../features/notifications/Notifications";
import { CoverActivity } from "../features/catalog/CoverActivity";
import { BrandLogo } from "../components/BrandLogo";
import { BackToTop } from "../components/BackToTop";
import { TorrentActivity } from "../features/downloads/TorrentActivity";
import { PreferencesProvider, usePreferences } from "./Preferences";
import {
  ImportProvider,
  useImport,
} from "../features/maintenance/ImportProvider";
import { Tooltip, Menu as AccountMenu } from "@mantine/core";
import { useMediaQuery } from "@mantine/hooks";
import { useTranslation } from "react-i18next";
import { catalogParams } from "../api/catalog";
import { useState } from "react";
import { Link, NavLink, Outlet, ScrollRestoration } from "react-router-dom";
import {
  Library,
  FolderOpen,
  Download,
  RefreshCw,
  ListChecks,
  Menu,
  X,
  ChevronsLeft,
  ChevronsRight,
  Settings,
  Bell,
  LogOut,
  UserRound,
} from "lucide-react";
export function Shell() {
  return (
    <PreferencesProvider>
      <NotificationsProvider>
        <ImportProvider>
          <ShellContent />
        </ImportProvider>
      </NotificationsProvider>
    </PreferencesProvider>
  );
}
function ShellContent() {
  const { notify } = useNotifications();
  const auth = useAuth();
  const unread = useUnreadEvents();
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const { collapsed, setCollapsed } = usePreferences();
  const { operation } = useImport();
  const downloadLinks = [
    {
      to: "/downloads",
      label: "nav.downloadState",
      Icon: Download,
      visible: auth.can("TORRENT_SYNC"),
    },
    {
      to: "/downloads/updates",
      label: "nav.updates",
      Icon: RefreshCw,
      visible: auth.can("CATALOG_READ") && auth.can("TORRENT_SYNC"),
    },
    {
      to: "/downloads/jobs",
      label: "nav.jobs",
      Icon: ListChecks,
      visible: auth.can("TORRENT_JOBS_MANAGE"),
    },
  ].filter((item) => item.visible);
  const mobile = useMediaQuery("(max-width: 700px)");
  return (
    <div className={`shell ${collapsed ? "sidebar-collapsed" : ""}`}>
      <a className="skip" href="#main">
        {t("nav.skip")}
      </a>
      <header className="mobile-header">
        <Link to="/" className="mobile-brand">
          <BrandLogo size={32} />
          EPL Sync
        </Link>
        <button
          aria-label={t(open ? "nav.close" : "nav.open")}
          aria-expanded={open}
          aria-controls="sidebar"
          onClick={() => setOpen(!open)}
        >
          {open ? <X /> : <Menu />}
        </button>
      </header>
      {open && (
        <button
          className="scrim"
          aria-label={t("nav.close")}
          onClick={() => setOpen(false)}
        />
      )}
      <aside
        id="sidebar"
        inert={mobile && !open}
        className={`sidebar ${open ? "open" : ""}`}
      >
        <div className="sidebar-heading">
          <Link
            className="brand"
            aria-label="EPL Sync"
            to="/"
            onClick={() => setOpen(false)}
          >
            <span className="brand-icon">
              <BrandLogo size={32} />
            </span>
            <span className="brand-name">
              EPL <strong>Sync</strong>
            </span>
          </Link>
        </div>
        <nav aria-label={t("nav.main")}>
          <div className="nav-group-label">
            <span>{t("nav.library")}</span>
          </div>
          {auth.can("CATALOG_READ") && (
            <NavLink
              to="/catalog"
              aria-label={t("nav.catalog")}
              title={t("nav.catalog")}
              onClick={() => setOpen(false)}
            >
              <Library size={19} />
              <span className="nav-text">{t("nav.catalog")}</span>
            </NavLink>
          )}
          {auth.can("CATALOG_READ") && (
            <NavLink
              to="/directory"
              aria-label={t("nav.directory")}
              title={t("nav.directory")}
              onClick={() => setOpen(false)}
            >
              <FolderOpen size={19} />
              <span className="nav-text">{t("nav.directory")}</span>
            </NavLink>
          )}
          {downloadLinks.length > 0 && (
            <div className="nav-group-label">
              <span>{t("nav.downloads")}</span>
            </div>
          )}
          {downloadLinks.map(({ to, label, Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === "/downloads"}
              aria-label={t(label)}
              title={t(label)}
              onClick={() => setOpen(false)}
            >
              <Icon size={19} />
              <span className="nav-text">{t(label)}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          {auth.can("EVENTS_MANAGE") && (
            <NavLink
              className="settings-link"
              to="/events"
              title={t("events.title")}
              aria-label={t("events.title")}
              onClick={() => setOpen(false)}
            >
              <Bell size={19} />
              <span className="nav-text">{t("events.title")}</span>
              {unread > 0 && (
                <span
                  className="event-unread-count"
                  role="status"
                  aria-label={t("events.unread", { count: unread })}
                  title={t("events.unread", { count: unread })}
                >
                  {unread > 99 ? "99+" : unread}
                </span>
              )}
            </NavLink>
          )}
          <NavLink
            className="settings-link"
            to="/settings"
            title={t("nav.settings")}
            aria-label={t("nav.settings")}
            onClick={() => setOpen(false)}
          >
            <Settings size={19} />
            <span className="nav-text">{t("nav.settings")}</span>
            {operation?.pending && (
              <span className="pending-dot" aria-label={t("import.pending")}>
                •
              </span>
            )}
          </NavLink>
          <div className="logout-section">
            <AccountMenu
              position="top-start"
              width={200}
              shadow="md"
              withinPortal
            >
              <AccountMenu.Target>
                <Tooltip
                  label={auth.user?.username}
                  disabled={!collapsed || mobile}
                  position="right"
                >
                  <button
                    className="settings-link account-link"
                    aria-label={auth.user?.username}
                    title={auth.user?.username}
                  >
                    <UserRound size={20} />
                    <span className="nav-text">{auth.user?.username}</span>
                  </button>
                </Tooltip>
              </AccountMenu.Target>
              <AccountMenu.Dropdown>
                <AccountMenu.Item
                  component={Link}
                  to="/settings/account"
                  leftSection={<UserRound size={16} />}
                  onClick={() => setOpen(false)}
                >
                  {t("auth.account")}
                </AccountMenu.Item>
                <AccountMenu.Divider />
                <AccountMenu.Item
                  leftSection={<LogOut size={16} />}
                  onClick={() =>
                    void auth
                      .logout()
                      .catch(() =>
                        notify({
                          title: t("auth.logout"),
                          message: t("auth.unavailable"),
                          tone: "error",
                        }),
                      )
                  }
                >
                  {t("auth.logout")}
                </AccountMenu.Item>
              </AccountMenu.Dropdown>
            </AccountMenu>
          </div>
          <div className="sidebar-fold-section">
            <Tooltip
              label={t(collapsed ? "nav.expand" : "nav.collapse")}
              position="right"
            >
              <button
                className="sidebar-toggle"
                aria-label={t(collapsed ? "nav.expand" : "nav.collapse")}
                aria-expanded={!collapsed}
                aria-controls="sidebar"
                onClick={() => setCollapsed(!collapsed)}
              >
                {collapsed ? (
                  <ChevronsRight size={20} />
                ) : (
                  <ChevronsLeft size={20} />
                )}
                <span className="nav-text">{t("nav.foldShort")}</span>
              </button>
            </Tooltip>
          </div>
        </div>
      </aside>
      <main id="main" tabIndex={-1}>
        {auth.can("EVENTS_MANAGE") && <EventConnection />}
        <TorrentActivity />
        {auth.can("COVERS_MANAGE") && <CoverActivity />}
        <RouteAccess>
          <Outlet />
        </RouteAccess>
      </main>
      <BackToTop hidden={open} />
      <ScrollRestoration
        getKey={(location) =>
          location.pathname +
          (location.pathname === "/catalog"
            ? "?" +
              catalogParams(
                new URLSearchParams(location.search),
                false,
              ).toString()
            : location.search)
        }
      />
    </div>
  );
}
