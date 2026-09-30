import { Alert, Button } from "@mantine/core";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Network } from "lucide-react";
interface Connection {
  enabled: boolean;
  connected: boolean;
  client: string | null;
  authMode: string | null;
  version: string | null;
  apiVersion: string | null;
}
class ConnectionError extends Error {
  constructor(
    public status: number,
    public details?: string,
  ) {
    super(`HTTP ${status}`);
  }
}
export function TorrentSettings() {
  const { t } = useTranslation();
  const check = useMutation({
    retry: false,
    mutationFn: async (): Promise<Connection> => {
      const response = await fetch("/api/torrent/client/connection", {
        cache: "no-store",
        headers: { Accept: "application/json" },
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new ConnectionError(
          response.status,
          typeof body?.details === "string" ? body.details : undefined,
        );
      }
      const body = await response.json();
      if (
        typeof body.enabled !== "boolean" ||
        typeof body.connected !== "boolean"
      )
        throw new ConnectionError(0);
      return body;
    },
  });
  const data = check.data;
  const error = check.error;
  return (
    <>
      <section className="panel settings-section">
        <h2>{t("torrent.title")}</h2>
        <p className="muted">{t("torrent.description")}</p>
        <Button
          leftSection={<Network size={18} />}
          loading={check.isPending}
          onClick={() => check.mutate()}
        >
          {t(check.isPending ? "torrent.checking" : "torrent.check")}
        </Button>
        <div className="connection-result" aria-live="polite">
          {data && (
            <>
              <Alert
                color={
                  !data.enabled ? "yellow" : data.connected ? "green" : "red"
                }
              >
                {t(
                  !data.enabled
                    ? "torrent.disabled"
                    : data.connected
                      ? "torrent.connected"
                      : "torrent.disconnected",
                )}
              </Alert>
              <dl className="import-summary">
                {(["client", "authMode", "version", "apiVersion"] as const).map(
                  (key) => (
                    <div key={key}>
                      <dt>{t(`torrent.${key}`)}</dt>
                      <dd>{data[key] || t("torrent.unavailable")}</dd>
                    </div>
                  ),
                )}
              </dl>
            </>
          )}
          {error && (
            <Alert color="red" title={t("torrent.failed")}>
              <p>
                {t(
                  error instanceof ConnectionError
                    ? error.status === 504
                      ? "torrent.timeout"
                      : error.status === 502
                        ? "torrent.upstream"
                        : error.status === 503
                          ? "torrent.interrupted"
                          : "torrent.httpError"
                    : "torrent.network",
                  {
                    status:
                      error instanceof ConnectionError ? error.status : "",
                  },
                )}
              </p>
              {error instanceof ConnectionError && error.details && (
                <p>
                  {t("torrent.serverDetail")}: {error.details}
                </p>
              )}
            </Alert>
          )}
        </div>
      </section>
    </>
  );
}
