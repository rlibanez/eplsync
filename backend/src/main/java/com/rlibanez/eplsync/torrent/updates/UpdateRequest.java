package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.torrent.bulk.*;

public record UpdateRequest(PreviousVersions previousVersions, TorrentDownloadRequest options,
        @com.fasterxml.jackson.annotation.JsonAlias("batch-size") Integer batchSize,
        Integer concurrency, String interval, MultipleHashes multipleHashes) {
    public BulkRequest bulk() { return new BulkRequest(options, batchSize, concurrency, interval, multipleHashes); }
    public PreviousVersions policy() { return previousVersions == null ? PreviousVersions.KEEP : previousVersions; }
}
