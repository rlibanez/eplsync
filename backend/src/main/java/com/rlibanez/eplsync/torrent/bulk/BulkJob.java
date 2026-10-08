package com.rlibanez.eplsync.torrent.bulk;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "torrent_bulk_jobs", indexes = @Index(name="idx_bulk_job_retention",columnList="state,updatedAt"))
@Getter @Setter
public class BulkJob {
    private String eventActorId;
    private String eventActorUsername;
    private String eventActorKind;
    public com.rlibanez.eplsync.events.EventContext.Actor eventActor() {
        return new com.rlibanez.eplsync.events.EventContext.Actor(eventActorId, eventActorUsername,
            eventActorKind == null ? "UNKNOWN" : eventActorKind);
    }
    private String eventOrigin = "MANUAL";
    @Id private String id;
    @Enumerated(EnumType.STRING) private State state;
    public enum State { QUEUED, RUNNING, RETRY_WAIT, PAUSED, COMPLETED, CANCELLED }
    public enum Type { DOWNLOAD, UPDATE }
    @Enumerated(EnumType.STRING) private Type type = Type.DOWNLOAD;
    @Enumerated(EnumType.STRING) private com.rlibanez.eplsync.torrent.updates.PreviousVersions previousVersions;
    private String client;
    private String targetFingerprint;
    @Enumerated(EnumType.STRING) private MultipleHashes multipleHashes;
    private int batchSize;
    private int concurrency;
    private long intervalMillis;
    private long selectedBooks;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant retryAt;
    private String message;
}
