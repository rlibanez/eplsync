import { secureFetch } from "../auth/transport";
import { ServerSettings } from "./ServerSettings";
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
    mutationKey: ["connection-check"],
    meta: { notice: { title: "torrent.title", href: "/settings/torrent" } },
    retry: false,
    mutationFn: async (
      values: Record<string, unknown>,
    ): Promise<Connection> => {
      const response = await secureFetch("/api/settings/torrent/connection", {
        method: "POST",
        body: JSON.stringify(values),
        cache: "no-store",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
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
  return (
    <ServerSettings
      section="torrent"
      actions={(values) => (
        <Button
          type="button"
          variant="light"
          title={t("torrent.description")}
          leftSection={<Network size={18} />}
          loading={check.isPending}
          onClick={() => check.mutate(values)}
        >
          {t(check.isPending ? "torrent.checking" : "torrent.check")}
        </Button>
      )}
    >
      <div className="connection-result" aria-live="polite">
        {check.isError && (
          <Alert color="red" title={t("torrent.failed")} role="alert">
            {check.error instanceof ConnectionError
              ? check.error.details ||
                t(
                  check.error.status ? "torrent.httpError" : "torrent.upstream",
                  {
                    status: check.error.status,
                  },
                )
              : t("torrent.network")}
          </Alert>
        )}
        {check.isSuccess && data && (
          <>
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
      </div>
    </ServerSettings>
  );
}
