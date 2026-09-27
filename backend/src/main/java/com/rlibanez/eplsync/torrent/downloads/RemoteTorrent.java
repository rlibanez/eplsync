package com.rlibanez.eplsync.torrent.downloads;

import java.time.Instant;

/** Estado observado por un adaptador; nunca incluye credenciales ni rutas locales. */
public record RemoteTorrent(String hash, DownloadStatus status, Instant completedAt) {}
