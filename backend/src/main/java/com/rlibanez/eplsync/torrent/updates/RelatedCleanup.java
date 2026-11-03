package com.rlibanez.eplsync.torrent.updates;

/** Narrow read mapping used to exclude managed cleanup requests from the legacy executor. */
@jakarta.persistence.Entity
@jakarta.persistence.Table(name="revision_cleanup_jobs")
public class RelatedCleanup {
    @jakarta.persistence.Id private String id;
    private String updateJobId;
}
