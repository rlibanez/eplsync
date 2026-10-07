package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;

/** Contrato común. Cada adaptador implementa su protocolo y autenticación. */
public interface TorrentClient {
    default com.rlibanez.eplsync.dto.TorrentDownloadResult.Status addTorrent(TorrentDownload download) {
        throw new TorrentOperationException(HttpStatus.UNPROCESSABLE_CONTENT,
                "El cliente seleccionado no admite añadir torrents");
    }

    default com.rlibanez.eplsync.dto.TorrentDownloadResult.Status addTorrent(
            TorrentDownload download, TorrentSubmissionContext context) {
        return addTorrent(download);
    }

    default TorrentDownload withDefaults(TorrentDownload download) { return download; }

    default java.util.List<com.rlibanez.eplsync.torrent.downloads.RemoteTorrent> listTorrents() {
        throw new TorrentOperationException(HttpStatus.UNPROCESSABLE_CONTENT,
                "El cliente seleccionado no admite sincronización de descargas");
    }

    default java.util.List<com.rlibanez.eplsync.torrent.downloads.RemoteTorrent> listTorrents(java.util.Set<String> hashes) {
        if (hashes.isEmpty()) return java.util.List.of();
        return listTorrents().stream().filter(t -> t.aliases().stream().anyMatch(hashes::contains)).toList();
    }

    default void deleteTorrent(String remoteId, boolean deleteFiles) {
        throw new TorrentOperationException(HttpStatus.UNPROCESSABLE_CONTENT,
                "El cliente seleccionado no admite eliminar torrents");
    }

    default java.util.List<String> listCategories() {
        throw new TorrentOperationException(HttpStatus.UNPROCESSABLE_CONTENT,
                "El cliente seleccionado no admite consultar categorías");
    }

    String type();
    TorrentConnectionStatus checkConnection();

    /** Cambia únicamente el nombre mostrado del torrent, nunca archivos o carpetas. */
    default void renameTorrent(String hash, String name) {
        throw new TorrentOperationException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "El cliente seleccionado no admite renombrar el torrent sin modificar archivos");
    }
}
