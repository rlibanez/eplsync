package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.torrent.downloads.RemoteTorrent;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Shared validation for explicit removal and deferred cleanup. Paths belong to the remote client. */
final class TorrentRemovalSafety {
    static Map<String, RemoteTorrent> index(List<RemoteTorrent> remote) {
        var result = new HashMap<String, RemoteTorrent>();
        for (var torrent : remote) for (var hash : torrent.aliases()) {
            var previous = result.putIfAbsent(hash, torrent);
            if (previous != null && !previous.hash().equals(torrent.hash()))
                throw new TorrentOperationException(HttpStatus.BAD_GATEWAY, "Identidades remotas ambiguas");
        }
        return result;
    }
    record Paths(NavigableMap<String, Integer> counts, boolean complete) {}
    static Paths paths(List<RemoteTorrent> remote) {
        var counts = new TreeMap<String, Integer>();
        boolean complete = true;
        for (var torrent : remote) {
            var path = normalize(torrent.contentPath());
            if (path == null) complete = false;
            else counts.merge(path, 1, Integer::sum);
        }
        return new Paths(counts, complete);
    }
    static boolean exclusivePath(RemoteTorrent torrent, Paths paths) {
        var path = normalize(torrent.contentPath());
        if (!paths.complete() || path == null || paths.counts().getOrDefault(path, 0) != 1) return false;
        for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1))
            if (paths.counts().containsKey(path.substring(0, slash))) return false;
        var descendant = paths.counts().ceilingKey(path + "/");
        return descendant == null || !descendant.startsWith(path + "/");
    }
    private static String normalize(String path) {
        if (path == null || !path.startsWith("/") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) return null;
        var parts = new ArrayList<String>();
        for (var part : path.split("/")) {
            if (part.equals("..")) return null;
            if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        return parts.isEmpty() ? null : "/" + String.join("/", parts);
    }
    private TorrentRemovalSafety() {}
}
