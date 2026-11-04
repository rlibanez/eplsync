package com.rlibanez.eplsync.torrent;

/** Successful live reads did not yet expose an acknowledged or uncertain addition. */
public final class TorrentPresenceUnconfirmedException extends com.rlibanez.eplsync.exception.TorrentConnectionException {
    public TorrentPresenceUnconfirmedException() { super(Reason.TIMEOUT); }
    @Override public String getMessage() {
        return "No se ha confirmado la presencia del torrent; se comprobará el hash antes de reintentar";
    }
}
