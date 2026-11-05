package com.rlibanez.eplsync.qbittorrent;

import com.rlibanez.eplsync.exception.TorrentConnectionException;

public class QBittorrentConnectionException extends TorrentConnectionException {
    private final String detail;
    public QBittorrentConnectionException(Reason reason) {this(reason,null);}
    public QBittorrentConnectionException(Reason reason,String detail) {super(reason);this.detail=detail;}
    @Override public String getMessage() {return detail==null?super.getMessage():detail;}
}
