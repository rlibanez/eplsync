package com.rlibanez.eplsync.dto;

import java.util.List;

/** null hereda el valor configurado; etiquetas vacías y categoría vacía lo eliminan. */
public record TorrentDownloadRequest(String hash, Boolean start, String savePath,
        Rename rename, QBittorrent qbittorrent) {
    public record Rename(Boolean enabled, String pattern) {}
    public record QBittorrent(String category, List<String> tags, Boolean autoManagement) {}
}
