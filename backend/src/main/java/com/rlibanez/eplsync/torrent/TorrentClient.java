package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;

/** Contrato común. Cada adaptador implementa su protocolo y autenticación. */
public interface TorrentClient {
    String type();
    TorrentConnectionStatus checkConnection();

    /** Cambia únicamente el nombre mostrado del torrent, nunca archivos o carpetas. */
    default void renameTorrent(String hash, String name) {
        throw new TorrentOperationException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "El cliente seleccionado no admite renombrar el torrent sin modificar archivos");
    }
}
