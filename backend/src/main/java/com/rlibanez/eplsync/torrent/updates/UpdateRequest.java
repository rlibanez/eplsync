package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.torrent.bulk.*;

public record UpdateRequest(PreviousVersions previousVersions, TorrentDownloadRequest options,
        @com.fasterxml.jackson.annotation.JsonAlias("batch-size") Integer batchSize,
        Integer concurrency, String interval, MultipleHashes multipleHashes, CleanupTiming cleanupTiming) {
    public UpdateRequest(PreviousVersions policy,TorrentDownloadRequest options,Integer batchSize,Integer concurrency,String interval,MultipleHashes hashes) {this(policy,options,batchSize,concurrency,interval,hashes,null);}
    public CleanupTiming timing() { return policy()==PreviousVersions.KEEP || cleanupTiming==null ? CleanupTiming.AFTER_DOWNLOAD : cleanupTiming; }
    public BulkRequest bulk() { return new BulkRequest(options, batchSize, concurrency, interval, multipleHashes); }
    public PreviousVersions policy() { return previousVersions == null ? PreviousVersions.KEEP : previousVersions; }
}
