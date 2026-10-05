import { useState } from "react";
import { Button } from "@mantine/core";
import { AlertTriangle, FileWarning } from "lucide-react";
import { useRouteError } from "react-router-dom";
import { useTranslation } from "react-i18next";

/** Eagerly loaded so failed route imports can still show a usable recovery view. */
export function RouteError() {
  const error = useRouteError();
  const { t } = useTranslation();
  const [reloading, setReloading] = useState(false);
  const failedImport = error instanceof Error &&
    /dynamically imported module|importing a module script|loading chunk|loading css chunk|failed to load module script/i.test(error.message);
  const Icon = failedImport ? FileWarning : AlertTriangle;
  return (
    <main className="connection-error-page">
      <section className="connection-error-card" aria-labelledby="route-error-title">
        <div className="connection-error-brand">EPL Sync</div>
        <div className="connection-error-icon" aria-hidden="true"><Icon size={28} /></div>
        <h1 id="route-error-title">{t(failedImport ? "routeError.loadTitle" : "routeError.title")}</h1>
        <p role="alert">{t(failedImport ? "routeError.loadMessage" : "routeError.message")}</p>
        <Button loading={reloading} onClick={() => {
          setReloading(true);
          window.location.reload();
        }}>{t("routeError.reload")}</Button>
      </section>
    </main>
  );
}
