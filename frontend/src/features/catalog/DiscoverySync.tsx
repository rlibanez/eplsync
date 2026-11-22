import { Alert, Button } from "@mantine/core";
import {
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Failure } from "../../components/Feedback";
import { formatDate } from "../../locales/formatDate";
import { useAuth } from "../auth/Auth";
import { post } from "../downloads/shared";

export function DiscoverySync() {
  const auth = useAuth();
  const { t, i18n } = useTranslation();
  const cache = useQueryClient();
  const downloads = auth.can("TORRENT_SYNC");
  const calibre = auth.can("CALIBRE_READ");
  const syncCalibre = calibre && auth.can("CALIBRE_SYNC");
  const downloadState = useQuery({
    queryKey: ["downloads", "sync-state"],
    enabled: downloads,
    queryFn: ({ signal }) =>
      get<{ syncedAt: string | null }>("/torrent/downloads/sync-state", signal),
  });
  const calibreState = useQuery({
    queryKey: ["calibre-snapshot"],
    enabled: calibre,
    queryFn: ({ signal }) =>
      get<{ snapshot: { syncedAt: string; complete?: boolean } | null }>(
        "/settings/calibre/snapshot",
        signal,
      ).then((data) => data.snapshot),
  });
  const torrentSync = useMutation({
    mutationKey: ["torrent-sync"],
    retry: false,
    mutationFn: () =>
      post("/torrent/downloads/sync", { dryRun: false, includeDetails: false }),
    onSuccess: async () => {
      await Promise.all([
        cache.invalidateQueries({ queryKey: ["downloads"] }),
        cache.invalidateQueries({ queryKey: ["discovery-values"] }),
        cache.invalidateQueries({ queryKey: ["book"] }),
      ]);
    },
  });
  const librarySync = useMutation({
    mutationKey: ["calibre-sync"],
    retry: false,
    mutationFn: () =>
      post<{ syncedAt: string; complete?: boolean }>(
        "/settings/calibre/sync",
        {},
      ),
    onSuccess: async (snapshot) => {
      cache.setQueryData(["calibre-snapshot"], snapshot);
      await Promise.all([
        cache.invalidateQueries({ queryKey: ["calibre-library"] }),
        cache.invalidateQueries({ queryKey: ["calibre-formats"] }),
        cache.invalidateQueries({ queryKey: ["discovery-values"] }),
        cache.invalidateQueries({ queryKey: ["book"] }),
      ]);
    },
  });
  const busy =
    useIsMutating({ mutationKey: ["torrent-sync"] }) +
      useIsMutating({ mutationKey: ["calibre-sync"] }) >
    0;
  const date = (value?: string | null) =>
    value
      ? formatDate(value, i18n.resolvedLanguage ?? "es")
      : t("discovery.neverSynced");
  return (
    <>
      <div className="page-heading">
        <h1>{t("discovery.title")}</h1>
        {(downloads || syncCalibre) && (
          <Button
            leftSection={<RefreshCw size={16} />}
            loading={busy}
            onClick={() => {
              if (busy) return;
              if (downloads) torrentSync.mutate();
              if (syncCalibre) librarySync.mutate();
            }}
          >
            {t(
              downloads && syncCalibre
                ? "discovery.syncAll"
                : downloads
                  ? "discovery.syncDownloads"
                  : "discovery.syncCalibre",
            )}
          </Button>
        )}
      </div>
      <p className="muted" aria-live="polite">
        {downloads && (
          <span>
            {t("discovery.downloadsSync")}:{" "}
            {torrentSync.isPending
              ? t("discovery.syncing")
              : date(downloadState.data?.syncedAt)}
          </span>
        )}
        {downloads && calibre && " · "}
        {calibre && (
          <span>
            {t("discovery.calibreSync")}:{" "}
            {librarySync.isPending
              ? t("discovery.syncing")
              : date(calibreState.data?.syncedAt)}
          </span>
        )}
      </p>
      {downloads && downloadState.isError && (
        <Failure
          error={downloadState.error}
          retry={() => void downloadState.refetch()}
        />
      )}
      {calibre && calibreState.isError && (
        <Failure
          error={calibreState.error}
          retry={() => void calibreState.refetch()}
        />
      )}
      {torrentSync.isError && (
        <Alert title={t("discovery.sources.DOWNLOADS")} color="red">
          <Failure
            error={torrentSync.error}
            retry={() => {
              if (!busy) torrentSync.mutate();
            }}
          />
        </Alert>
      )}
      {librarySync.isError && (
        <Alert title={t("discovery.sources.CALIBRE")} color="red">
          <Failure
            error={librarySync.error}
            retry={() => {
              if (!busy) librarySync.mutate();
            }}
          />
        </Alert>
      )}
      {calibre && calibreState.data?.complete === false && (
        <Alert color="yellow">{t("calibreSend.partial")}</Alert>
      )}
    </>
  );
}
