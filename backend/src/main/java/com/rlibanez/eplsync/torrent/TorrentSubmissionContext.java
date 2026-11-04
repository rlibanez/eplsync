package com.rlibanez.eplsync.torrent;

import java.util.Set;
import java.util.function.Supplier;

/** Estado efímero exclusivo de un envío individual o una ejecución bulk. */
public final class TorrentSubmissionContext {
    private Set<String> categories;
    private Set<String> torrentHashes;
    private com.rlibanez.eplsync.exception.TorrentConnectionException hashFailure;
    private long hashRetryAt;
    private long indexObservedAt;
    private final java.util.Map<String,Long> confirmedAt = new java.util.HashMap<>();
    private final java.util.function.LongSupplier clock;
    public TorrentSubmissionContext() {this(System::nanoTime);}
    TorrentSubmissionContext(java.util.function.LongSupplier clock) {this.clock=clock;}

    public synchronized boolean containsTorrent(String hash, Supplier<Set<String>> loader) {
        if (torrentHashes == null) {
            if(hashFailure!=null && clock.getAsLong()-hashRetryAt<0) throw hashFailure;
            try {torrentHashes=new java.util.HashSet<>(loader.get());hashFailure=null;indexObservedAt=System.nanoTime();}
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

    public synchronized void invalidateTorrents() {
        torrentHashes = null;
    }


    private final Set<String> recovering = new java.util.HashSet<>();
    public synchronized void recovering(String hash) { recovering.add(hash.toUpperCase(java.util.Locale.ROOT)); }
    public synchronized boolean takeRecovery(String hash) { return recovering.remove(hash.toUpperCase(java.util.Locale.ROOT)); }

    private final java.util.Map<String,Integer> awaiting = new java.util.HashMap<>();
    private final java.util.Map<String,Long> checkedAt = new java.util.HashMap<>();
    private boolean checking;
    private long nextCheck;
    private RuntimeException confirmationFailure;

    /** Coalesce simultaneous additions into bounded live reads; POST acknowledgement is never evidence. */
    public void confirmTorrent(String hash, java.util.function.Function<Set<String>, Set<String>> loader) {
        confirmTorrent(hash, loader, java.time.Duration.ofSeconds(30));
    }

    void confirmTorrent(String hash, java.util.function.Function<Set<String>, Set<String>> loader, java.time.Duration timeout) {
        String normalized = hash.toUpperCase(java.util.Locale.ROOT);
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) { awaiting.merge(normalized,1,Integer::sum); }
        try {
            while (true) {
                Set<String> batch;
                synchronized (this) {
                    long now=System.nanoTime();
                    if (confirmedAt.containsKey(normalized) && now-confirmedAt.get(normalized)<java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
                            || torrentHashes != null && torrentHashes.contains(normalized) && now-indexObservedAt<java.util.concurrent.TimeUnit.SECONDS.toNanos(1)) return;
                    if (confirmationFailure != null) throw confirmationFailure;
                    if (now >= deadline) {
                        if (checkedAt.containsKey(normalized) && now-checkedAt.get(normalized)<java.util.concurrent.TimeUnit.SECONDS.toNanos(2))
                            throw new TorrentPresenceUnconfirmedException();
                        throw new com.rlibanez.eplsync.exception.TorrentConnectionException(
                            com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.TIMEOUT);
                    }
                    boolean fresh=awaiting.keySet().stream().anyMatch(value->!checkedAt.containsKey(value));
                    if (checking || !fresh && System.nanoTime() < nextCheck) { waitForConfirmation(100); continue; }
                    checking = true;
                    // Give concurrent POST responses a short opportunity to join this read.
                    try { waitForConfirmation(50); }
                    catch (RuntimeException ex) { checking=false; notifyAll(); throw ex; }
                    batch = awaiting.keySet().stream().limit(100).collect(java.util.stream.Collectors.toSet());
                }
                try {
                    Set<String> observed = loader.apply(Set.copyOf(batch));
                    synchronized (this) {
                        // A partial confirmation read must never replace the complete pre-send index.
                        if (torrentHashes != null) torrentHashes.addAll(observed);
                        long now=System.nanoTime();observed.forEach(value->confirmedAt.put(value,now));
                        batch.forEach(value->checkedAt.put(value,now));
                        nextCheck = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
                    }
                } catch (RuntimeException ex) {
                    synchronized (this) { confirmationFailure = ex; }
                    throw ex;
                } finally {
                    synchronized (this) { checking = false; notifyAll(); }
                }
            }
        } finally {
            synchronized (this) {
                awaiting.computeIfPresent(normalized,(key,count)->count==1?null:count-1);
                if(!awaiting.containsKey(normalized)) checkedAt.remove(normalized);
                notifyAll();
            }
        }
    }

    private void waitForConfirmation(long millis) {
        try { wait(millis); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.rlibanez.eplsync.exception.TorrentConnectionException(
                com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.INTERRUPTED);
        }
    }

    public synchronized Set<String> categories(Supplier<Set<String>> loader) {
        if (categories == null) categories = Set.copyOf(loader.get());
        return categories;
    }
}
