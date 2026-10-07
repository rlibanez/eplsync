package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import com.rlibanez.eplsync.torrent.TorrentClient;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TorrentClientService {
    private final TorrentProperties properties;
    private final TorrentClient selectedClient;
    private final DownloadTrackingService tracking;

    public TorrentClientService(TorrentProperties properties, List<TorrentClient> clients,
            DownloadTrackingService tracking) {
        this.properties = properties;
        this.tracking = tracking;
        var matches = clients.stream().filter(client -> client.type().equals(properties.getClient())).toList();
        if (properties.isEnabled() && matches.size() != 1) {
            throw new com.rlibanez.eplsync.exception.UserInputException("Configuración torrent: client debe seleccionar un único adaptador implementado");
        }
        selectedClient = matches.size() == 1 ? matches.getFirst() : null;
    }

    public TorrentConnectionStatus checkConnection() {
        if (selectedClient == null) throw new TorrentOperationException(HttpStatus.CONFLICT,
                "No hay un adaptador disponible para el cliente torrent configurado");
        return selectedClient.checkConnection();
    }

    public List<String> listCategories() {
        requireEnabled();
        return selectedClient.listCategories();
    }

    public void requireEnabled() {
        if (!properties.isEnabled()) throw new TorrentOperationException(
                HttpStatus.CONFLICT, "La conexión torrent está deshabilitada");
    }

    public com.rlibanez.eplsync.dto.TorrentDownloadResult.Status addTorrent(
            com.rlibanez.eplsync.torrent.TorrentDownload download) {
        requireEnabled();
        return tracking.submit(download, () -> selectedClient.addTorrent(download));
    }

    public com.rlibanez.eplsync.dto.TorrentDownloadResult.Status addTorrent(
            com.rlibanez.eplsync.torrent.TorrentDownload download,
            com.rlibanez.eplsync.torrent.TorrentSubmissionContext context) {
        requireEnabled();
        return tracking.submit(download, () -> selectedClient.addTorrent(download, context));
    }

    public com.rlibanez.eplsync.torrent.TorrentDownload withDefaults(com.rlibanez.eplsync.torrent.TorrentDownload download) {
        requireEnabled();
        return selectedClient.withDefaults(download);
    }

    public com.rlibanez.eplsync.torrent.downloads.DownloadRecord linkDownload(DownloadTrackingService.LinkRequest request) {
        requireEnabled();
        return tracking.link(request, selectedClient::listTorrents);
    }

    public DownloadTrackingService.SyncResult syncDownloads(boolean dryRun, boolean includeDetails) {
        return tracking.sync(() -> { requireEnabled(); return selectedClient.listTorrents(); }, dryRun, includeDetails);
    }

    public <T> T exclusiveClient(java.util.function.Function<TorrentClient, T> action) {
        requireEnabled();
        return tracking.exclusive(() -> {
            if (selectedClient instanceof com.rlibanez.eplsync.qbittorrent.QBittorrentClient qbittorrent)
                return qbittorrent.withSnapshot(snapshot -> tracking.withInstance(snapshot.type(),snapshot.snapshotBaseUrl(),() -> action.apply(snapshot)));
            return tracking.withInstance(properties.getClient(),properties.getBaseUrl(),() -> action.apply(selectedClient));
        });
    }

    public void requireRenameEnabled() {
        if (!properties.isEnabled() || !properties.getRename().isEnabled()) {
            throw new TorrentOperationException(
                    HttpStatus.CONFLICT,
                    "La conexión torrent o el renombrado están deshabilitados");
        }
    }

    public void renameTorrent(String hash, String name) {
        requireRenameEnabled();
        selectedClient.renameTorrent(hash, name);
    }
}
