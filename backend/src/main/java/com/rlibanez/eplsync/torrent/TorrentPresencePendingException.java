package com.rlibanez.eplsync.torrent;

/** Another live check is still pending: this is not evidence of absence or transport failure. */
public final class TorrentPresencePendingException extends com.rlibanez.eplsync.exception.TorrentConnectionException {
    public TorrentPresencePendingException() {super(Reason.TIMEOUT);}
    @Override public String getMessage() {return "La comprobación de presencia sigue pendiente; se comprobará automáticamente antes de repetir el envío";}
}
