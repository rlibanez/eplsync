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
    record Paths(NavigableMap<String, Integer> counts, Set<String> uncertainRoots, boolean complete) {}
    enum PathSafety { EXCLUSIVE, UNKNOWN, SHARED }
    static Paths paths(List<RemoteTorrent> remote) {
        var counts = new TreeMap<String, Integer>();
        boolean complete = true;
        var uncertainRoots=new HashSet<String>();
        for (var torrent : remote) {
            var path = normalize(torrent.contentPath());
            if (path == null) {
                // No current file paths exist until metadata arrives. Never apply this exception to the deletion target.
                if (torrent.awaitingMetadataWithoutData() && (torrent.contentPath()==null || torrent.contentPath().isBlank())) continue;
                if(torrent.contentPath()!=null && !torrent.contentPath().isBlank()) {complete=false;continue;}
                var root=normalize(torrent.savePath());
                if(root==null) complete=false; else uncertainRoots.add(root);
            }
            else counts.merge(path, 1, (left, right) -> java.util.Objects.requireNonNull(left) + java.util.Objects.requireNonNull(right));
        }
        return new Paths(counts, Set.copyOf(uncertainRoots), complete);
    }
    static boolean exclusivePath(RemoteTorrent torrent, Paths paths) {return assess(torrent,paths)==PathSafety.EXCLUSIVE;}
    static PathSafety assess(RemoteTorrent torrent, Paths paths) {
        var path=normalize(torrent.contentPath());
        if(path==null) return PathSafety.UNKNOWN;
        if(paths.counts().getOrDefault(path,0)!=1) return PathSafety.SHARED;
        for(int slash=path.lastIndexOf('/');slash>0;slash=path.lastIndexOf('/',slash-1))
            if(paths.counts().containsKey(path.substring(0,slash))) return PathSafety.SHARED;
        var descendant=paths.counts().ceilingKey(path+"/");
        if(descendant!=null && descendant.startsWith(path+"/")) return PathSafety.SHARED;
        if(!paths.complete() || paths.uncertainRoots().stream().anyMatch(root -> path.equals(root) || path.startsWith(root+"/") || root.startsWith(path+"/"))) return PathSafety.UNKNOWN;
        return PathSafety.EXCLUSIVE;
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
