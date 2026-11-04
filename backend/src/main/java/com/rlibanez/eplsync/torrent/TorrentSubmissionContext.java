package com.rlibanez.eplsync.torrent;

import java.util.Set;
import java.util.function.Supplier;

/** Estado efímero exclusivo de un envío individual o una ejecución bulk. */
public final class TorrentSubmissionContext {
    private Set<String> categories;
    private Set<String> torrentHashes;
    private com.rlibanez.eplsync.exception.TorrentConnectionException hashFailure;
    private long hashRetryAt;
    private final java.util.function.LongSupplier clock;
    public TorrentSubmissionContext() {this(System::nanoTime);}
    TorrentSubmissionContext(java.util.function.LongSupplier clock) {this.clock=clock;}

    public synchronized boolean containsTorrent(String hash, Supplier<Set<String>> loader) {
        if (torrentHashes == null) {
            if(hashFailure!=null && clock.getAsLong()-hashRetryAt<0) throw hashFailure;
            try {torrentHashes=new java.util.HashSet<>(loader.get());hashFailure=null;}
            catch(com.rlibanez.eplsync.exception.TorrentConnectionException ex) {
                if(ex.getReason()==com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.UPSTREAM || ex.getReason()==com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.TIMEOUT) {
                    // Queued callers share this failed read; no caller may POST without a verified index.
                    hashFailure=ex;hashRetryAt=clock.getAsLong()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                }
                throw ex;
            }
        }
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
