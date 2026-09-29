package com.rlibanez.eplsync.torrent;

import java.util.Set;
import java.util.function.Supplier;

/** Estado efímero exclusivo de un envío individual o una ejecución bulk. */
public final class TorrentSubmissionContext {
    private Set<String> categories;
    private Set<String> torrentHashes;

    public synchronized boolean containsTorrent(String hash, Supplier<Set<String>> loader) {
        if (torrentHashes == null) torrentHashes = new java.util.HashSet<>(loader.get());
        return torrentHashes.contains(hash.toUpperCase(java.util.Locale.ROOT));
    }

    public synchronized void torrentAccepted(String hash) {
        if (torrentHashes != null) torrentHashes.add(hash.toUpperCase(java.util.Locale.ROOT));
    }

    public synchronized void invalidateTorrents() {
        torrentHashes = null;
    }


    public synchronized Set<String> categories(Supplier<Set<String>> loader) {
        if (categories == null) categories = Set.copyOf(loader.get());
        return categories;
    }
}
