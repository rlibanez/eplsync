package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import com.rlibanez.eplsync.torrent.TorrentClient;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TorrentClientService {
    private final TorrentProperties properties;
    private final TorrentClient selectedClient;

    public TorrentClientService(TorrentProperties properties, List<TorrentClient> clients) {
        this.properties = properties;
        var matches = clients.stream().filter(client -> client.type().equals(properties.getClient())).toList();
        if (properties.isEnabled() && matches.size() != 1) {
            throw new IllegalArgumentException("Configuración torrent: client debe seleccionar un único adaptador implementado");
        }
        selectedClient = matches.size() == 1 ? matches.getFirst() : null;
    }

    public TorrentConnectionStatus checkConnection() {
        if (!properties.isEnabled()) {
            return new TorrentConnectionStatus(false, false, properties.getClient(), null, null, null);
        }
        return selectedClient.checkConnection();
    }

    public void requireEnabled() {
        if (!properties.isEnabled()) throw new TorrentOperationException(
                HttpStatus.CONFLICT, "La conexión torrent está deshabilitada");
    }

    public com.rlibanez.eplsync.dto.TorrentDownloadResult.Status addTorrent(
            com.rlibanez.eplsync.torrent.TorrentDownload download) {
        requireEnabled();
        return selectedClient.addTorrent(download);
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
