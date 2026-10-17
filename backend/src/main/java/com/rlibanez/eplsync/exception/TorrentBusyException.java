package com.rlibanez.eplsync.exception;

import org.springframework.http.HttpStatus;

/** Local coordination is busy; background consumers should defer instead of pausing their jobs. */
public final class TorrentBusyException extends TorrentOperationException {
    public TorrentBusyException() {
        super(HttpStatus.CONFLICT,"Hay envíos o una sincronización en curso; vuelve a intentarlo al terminar");
    }
}
