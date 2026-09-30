import { useTranslation } from "react-i18next";
import { createBrowserRouter, Link, Navigate } from "react-router-dom";
import { Shell } from "../layout/Shell";
import { SettingsLayout } from "../features/settings/SettingsLayout";
import { Home } from "../features/home/Home";
export const router = createBrowserRouter([
  {
    element: <Shell />,
    children: [
      {
        path: "/maintenance/catalog",
        element: <Navigate to="/settings/database" replace />,
      },
      {
        path: "/settings",
        element: <SettingsLayout />,
        children: [
          { index: true, element: <Navigate to="general" replace /> },
          {
            path: "general",
            lazy: async () => ({
              Component: (await import("../features/settings/Settings"))
                .Settings,
            }),
          },
          {
            path: "database",
            lazy: async () => ({
              Component: (await import("../features/maintenance/ImportCatalog"))
                .ImportCatalog,
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
        path: "/downloads/send",
        lazy: async () => ({
          Component: (await import("../features/downloads/SendBooks"))
            .SendBooks,
        }),
      },
      {
        path: "/downloads/send/multiple",
        lazy: async () => ({
          Component: (await import("../features/downloads/SendBooks"))
            .SendBooks,
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
      { path: "/", element: <Home /> },
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
