package com.rlibanez.eplsync.exception;

import org.springframework.http.HttpStatus;

/** Local coordination is busy; background consumers should defer instead of pausing their jobs. */
public final class TorrentBusyException extends TorrentOperationException {
    public TorrentBusyException() {
        this("Hay otra operación con el cliente torrent en curso; vuelve a intentarlo cuando termine");
    }
    public TorrentBusyException(String message) {super(HttpStatus.CONFLICT,message);}
}
