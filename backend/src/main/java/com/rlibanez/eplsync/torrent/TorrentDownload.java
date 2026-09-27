package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;

public record TorrentDownload(String hash, String magnet, boolean start, String savePath,
        String name, TorrentDownloadRequest.QBittorrent qbittorrent,
        com.rlibanez.eplsync.model.CatalogBook book) {}
