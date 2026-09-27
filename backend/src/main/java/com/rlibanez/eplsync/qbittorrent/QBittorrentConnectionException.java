package com.rlibanez.eplsync.qbittorrent;

import com.rlibanez.eplsync.exception.TorrentConnectionException;

public class QBittorrentConnectionException extends TorrentConnectionException {
    public QBittorrentConnectionException(Reason reason) { super(reason); }
}
