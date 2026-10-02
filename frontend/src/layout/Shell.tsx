import { useUnreadEvents } from "../features/events/useUnreadEvents";
import { EventConnection } from "../features/events/EventConnection";
import { NotificationsProvider } from "../features/notifications/Notifications";
import { CoverActivity } from "../features/catalog/CoverActivity";
import { BackToTop } from "../components/BackToTop";
import { TorrentActivity } from "../features/downloads/TorrentActivity";
import { PreferencesProvider, usePreferences } from "./Preferences";
import {
  ImportProvider,
  useImport,
} from "../features/maintenance/ImportProvider";
import { useMediaQuery } from "@mantine/hooks";
import { useTranslation } from "react-i18next";
import { catalogParams } from "../api/catalog";
import { useState } from "react";
import { Link, NavLink, Outlet, ScrollRestoration } from "react-router-dom";
import {
  BookOpen,
  Library,
  FolderOpen,
  Download,
  Send,
  ListChecks,
  Menu,
  X,
  PanelLeftClose,
  PanelLeftOpen,
  Settings,
  Bell,
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
  const unread = useUnreadEvents();
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const { collapsed, setCollapsed } = usePreferences();
  const { operation } = useImport();
  const mobile = useMediaQuery("(max-width: 700px)");
  return (
    <div className={`shell ${collapsed ? "sidebar-collapsed" : ""}`}>
      <a className="skip" href="#main">
        {t("nav.skip")}
      </a>
      <header className="mobile-header">
        <Link to="/">EPL Sync</Link>
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
              <BookOpen size={24} />
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
          <NavLink
            to="/catalog"
            aria-label={t("nav.catalog")}
            title={t("nav.catalog")}
            onClick={() => setOpen(false)}
          >
            <Library size={19} />
            <span className="nav-text">{t("nav.catalog")}</span>
          </NavLink>
          <NavLink
            to="/directory"
            aria-label={t("nav.directory")}
            title={t("nav.directory")}
            onClick={() => setOpen(false)}
          >
            <FolderOpen size={19} />
            <span className="nav-text">{t("nav.directory")}</span>
          </NavLink>
          <div className="nav-group-label">
            <span>{t("nav.downloads")}</span>
          </div>
          {[
            { to: "/downloads", label: "nav.downloadState", Icon: Download },
            { to: "/downloads/send", label: "nav.send", Icon: Send },
            { to: "/downloads/jobs", label: "nav.jobs", Icon: ListChecks },
          ].map(({ to, label, Icon }) => (
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
          <button
            className="sidebar-toggle"
            aria-label={t(collapsed ? "nav.expand" : "nav.collapse")}
            title={t(collapsed ? "nav.expand" : "nav.collapse")}
            aria-expanded={!collapsed}
            aria-controls="sidebar"
            onClick={() => setCollapsed(!collapsed)}
          >
            {collapsed ? (
              <PanelLeftOpen size={20} />
            ) : (
              <PanelLeftClose size={20} />
            )}
            <span className="nav-text">{t("nav.foldShort")}</span>
          </button>
        </div>
      </aside>
      <main id="main" tabIndex={-1}>
        <EventConnection />
        <TorrentActivity />
        <CoverActivity />
        <Outlet />
      </main>
      <BackToTop hidden={open} />
      <ScrollRestoration
        getKey={(location) =>
          location.pathname +
          (location.pathname === "/catalog"
            ? "?" +
              catalogParams(new URLSearchParams(location.search)).toString()
            : location.search)
        }
      />
    </div>
  );
}
