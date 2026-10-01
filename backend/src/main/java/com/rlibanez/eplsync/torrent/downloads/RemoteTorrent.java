package com.rlibanez.eplsync.torrent.downloads;

import java.time.Instant;

/** Estado observado por un adaptador; nunca incluye credenciales; contentPath pertenece al cliente remoto. */
public record RemoteTorrent(String hash, DownloadStatus status, Instant completedAt,
        java.util.Set<String> aliases, String contentPath, String name) {
    public RemoteTorrent {
        hash = hash.toUpperCase(java.util.Locale.ROOT);
        var normalized = new java.util.HashSet<String>();
        normalized.add(hash);
        aliases.forEach(value -> normalized.add(value.toUpperCase(java.util.Locale.ROOT)));
        aliases = java.util.Set.copyOf(normalized);
    }

    public RemoteTorrent(String hash, DownloadStatus status, Instant completedAt, java.util.Set<String> aliases, String contentPath) {
        this(hash, status, completedAt, aliases, contentPath, null);
    }
    public RemoteTorrent(String hash, DownloadStatus status, Instant completedAt, java.util.Set<String> aliases) {
        this(hash, status, completedAt, aliases, null);
    }

    public RemoteTorrent(String hash, DownloadStatus status, Instant completedAt) {
        this(hash, status, completedAt, java.util.Set.of(), null);
    }
}
