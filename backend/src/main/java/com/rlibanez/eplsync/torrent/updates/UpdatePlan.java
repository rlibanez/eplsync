package com.rlibanez.eplsync.torrent.updates;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "torrent_update_plans") @Getter @Setter
public class UpdatePlan {
    private Boolean automaticCleanup = false;
    @Id private String jobId;
    @Column(nullable = false) private String clientInstanceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PreviousVersions previousVersions;
    @Enumerated(EnumType.STRING) private CleanupTiming cleanupTiming;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false, columnDefinition = "TEXT") private String snapshot;
}
