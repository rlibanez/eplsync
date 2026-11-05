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
    private java.time.Duration confirmationWindow=java.time.Duration.ofSeconds(30);
    public TorrentSubmissionContext() {this(System::nanoTime);}
    public static TorrentSubmissionContext bulk() {var value=new TorrentSubmissionContext();value.confirmationWindow=java.time.Duration.ofSeconds(3);return value;}
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


    private final java.util.Map<String,Boolean> recovering = new java.util.HashMap<>();
    public void recovering(String hash) {recovering(hash,true);}
    public synchronized void recovering(String hash,boolean mayResubmit) {recovering.put(hash.toUpperCase(java.util.Locale.ROOT),mayResubmit);}
    public synchronized java.util.Optional<Boolean> takeRecovery(String hash) {return java.util.Optional.ofNullable(recovering.remove(hash.toUpperCase(java.util.Locale.ROOT)));}
    /** An uncertain POST invalidates only that hash, not the index and categories of 65000 others. */
    public synchronized void uncertain(String hash) {
        String value=hash.toUpperCase(java.util.Locale.ROOT);
        if(torrentHashes!=null) torrentHashes.remove(value);confirmedAt.remove(value);recovering.put(value,true);
    }
    public synchronized void resumed() {confirmationFailure=null;}

    private final java.util.Map<String,Set<String>> primaryIds=new java.util.HashMap<>();
    public synchronized void acknowledged(String hash,Set<String> ids) {
        if(!ids.isEmpty()) primaryIds.put(hash.toUpperCase(java.util.Locale.ROOT),Set.copyOf(ids));
    }
    public synchronized Set<String> queryIds(Set<String> hashes) {
        return hashes.stream().flatMap(hash->primaryIds.getOrDefault(hash,Set.of(hash)).stream()).collect(java.util.stream.Collectors.toSet());
    }
    public synchronized Set<String> matchedRequests(Set<String> hashes,Set<String> observed) {
        var result=new java.util.HashSet<>(observed);
        for(var hash:hashes) if(primaryIds.getOrDefault(hash,Set.of(hash)).stream().anyMatch(observed::contains)) result.add(hash);
        return result;
    }
    private boolean fallbackRead,fallbackRunning;
    private long fallbackAt;
    private Set<String> fallbackHashes=Set.of();
    public Set<String> aliasFallback(Supplier<Set<String>> loader) {
        synchronized(this) {
            long now=clock.getAsLong();
            if(fallbackRead && now-fallbackAt<java.util.concurrent.TimeUnit.SECONDS.toNanos(30))
                return now-fallbackAt<java.util.concurrent.TimeUnit.SECONDS.toNanos(1)?fallbackHashes:Set.of();
            if(fallbackRunning) return Set.of();
            fallbackRunning=true;fallbackRead=true;fallbackAt=now;fallbackHashes=Set.of();
        }
        try {
            var values=Set.copyOf(loader.get());
            synchronized(this) {fallbackHashes=values;if(torrentHashes!=null) torrentHashes.addAll(values);}
            return values;
        } finally {synchronized(this) {fallbackRunning=false;}}
    }

    private final java.util.Map<String,Integer> awaiting = new java.util.HashMap<>();
    private final java.util.Map<String,Long> checkedAt = new java.util.HashMap<>();
    private boolean checking;
    private long nextCheck;
    private RuntimeException confirmationFailure;
    private long confirmationRetryAt;

    /** Coalesce simultaneous additions into bounded live reads; POST acknowledgement is never evidence. */
    public void confirmTorrent(String hash, java.util.function.Function<Set<String>, Set<String>> loader) {
        confirmTorrent(hash, loader, confirmationWindow);
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
                    if (confirmationFailure != null) {
                        if(clock.getAsLong()-confirmationRetryAt<0) throw confirmationFailure;
                        confirmationFailure=null;
                    }
                    if (now >= deadline) {
                        if (checkedAt.containsKey(normalized) && now-checkedAt.get(normalized)<java.util.concurrent.TimeUnit.SECONDS.toNanos(2))
                            throw new TorrentPresenceUnconfirmedException();
                        throw new TorrentPresencePendingException();
                    }
                    boolean fresh=awaiting.keySet().stream().anyMatch(value->!checkedAt.containsKey(value));
                    if (checking || !fresh && System.nanoTime() < nextCheck) { waitForConfirmation(100); continue; }
                    checking = true;
                    // Give concurrent POST responses a short opportunity to join this read.
                    try { waitForConfirmation(confirmationWindow.getSeconds()<30?5:50); }
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
                    synchronized (this) { confirmationFailure = ex;confirmationRetryAt=clock.getAsLong()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5); }
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
