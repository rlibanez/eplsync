import { RouteError } from "../components/RouteError";
import { AccountSettings } from "../features/auth/Auth";
import { useTranslation } from "react-i18next";
import { createBrowserRouter, Link, Navigate } from "react-router-dom";
import { Shell } from "../layout/Shell";
export const router = createBrowserRouter([
  {
    element: <Shell />,
    errorElement: <RouteError />,
    children: [
      {
        path: "/downloads/sync",
        element: <Navigate to="/downloads" replace />,
      },
      {
        path: "/events",
        lazy: async () => ({
          Component: (await import("../features/events/Events")).Events,
        }),
      },
      {
        path: "/maintenance/catalog",
        element: <Navigate to="/settings/catalog" replace />,
      },
      {
        path: "/settings",
        lazy: async () => ({
          Component: (await import("../features/settings/SettingsLayout"))
            .SettingsLayout,
        }),
        children: [
          {
            path: "database",
            lazy: async () => ({
              Component: (await import("../features/auth/DatabaseReset"))
                .DatabaseReset,
            }),
          },
          {
            path: "home",
            lazy: async () => ({
              Component: (await import("../features/settings/HomeSettings"))
                .HomeSettings,
            }),
          },
          { path: "account", element: <AccountSettings /> },
          {
            path: "users",
            lazy: async () => ({
              Component: (await import("../features/auth/UserSettings"))
                .UserSettings,
            }),
          },
          {
            path: "reset",
            element: <Navigate to="/settings/database" replace />,
          },
          {
            path: "missing",
            element: <Navigate to="/settings/catalog" replace />,
          },
          { index: true, element: <Navigate to="general" replace /> },
          {
            path: "general",
            lazy: async () => ({
              Component: (await import("../features/settings/Settings"))
                .Settings,
            }),
          },
          {
            path: "catalog",
            lazy: async () => ({
              Component: (await import("../features/maintenance/ImportCatalog"))
                .ImportCatalog,
            }),
          },
          {
            path: "events",
            lazy: async () => ({
              Component: (await import("../features/settings/EventSettings"))
                .EventSettings,
            }),
          },
          {
            path: "covers",
            element: <Navigate to="/settings/catalog" replace />,
          },
          {
            path: "downloads",
            lazy: async () => ({
              Component: (await import("../features/settings/DownloadSettings"))
                .DownloadSettings,
            }),
          },
          {
            path: "about",
            lazy: async () => ({
              Component: (await import("../features/settings/About")).About,
            }),
          },
          {
            path: "torrent",
            lazy: async () => ({
              Component: (await import("../features/settings/TorrentSettings"))
                .TorrentSettings,
            }),
          },
        ],
      },
      {
        path: "/directory",
        lazy: async () => ({
          Component: (await import("../features/directory/Directory"))
            .Directory,
        }),
      },
      {
        path: "/downloads",
        lazy: async () => ({
          Component: (await import("../features/downloads/DownloadState"))
            .DownloadState,
        }),
      },
      {
        path: "/downloads/updates",
        lazy: async () => ({
          Component: (await import("../features/downloads/RevisionUpdates"))
            .RevisionUpdates,
        }),
      },
      {
        path: "/downloads/jobs",
        lazy: async () => ({
          Component: (await import("../features/downloads/Jobs")).Jobs,
        }),
      },
      {
        path: "/downloads/jobs/:id",
        lazy: async () => ({
          Component: (await import("../features/downloads/Jobs")).JobDetail,
        }),
      },
      {
        path: "/",
        lazy: async () => ({
          Component: (await import("../features/home/Home")).Home,
        }),
      },
      {
        path: "/catalog",
        lazy: async () => ({
          Component: (await import("../features/catalog/Catalog")).Catalog,
        }),
      },
      {
        path: "/catalog/:id",
        lazy: async () => ({
          Component: (await import("../features/catalog/BookDetail"))
            .BookDetail,
        }),
      },
      {
        path: "*",
        element: <NotFound />,
      },
    ],
  },
]);

function NotFound() {
  const { t } = useTranslation();
  return (
    <div className="feedback">
      <h1>{t("notFound.title")}</h1>
      <Link to="/">{t("notFound.home")}</Link>
    </div>
  );
}
