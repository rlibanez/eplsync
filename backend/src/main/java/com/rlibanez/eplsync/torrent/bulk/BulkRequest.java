package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;

public record BulkRequest(TorrentDownloadRequest options, @com.fasterxml.jackson.annotation.JsonAlias("batch-size") Integer batchSize,
        Integer concurrency, String interval, MultipleHashes multipleHashes) {
    public BulkRequest(TorrentDownloadRequest options, Integer batchSize, Integer concurrency, String interval) {
        this(options, batchSize, concurrency, interval, null);
    }
}
