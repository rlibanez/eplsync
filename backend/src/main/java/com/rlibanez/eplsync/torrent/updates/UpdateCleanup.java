package com.rlibanez.eplsync.torrent.updates;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "torrent_update_cleanup", uniqueConstraints = @UniqueConstraint(columnNames = {"jobId", "downloadId"}))
@Getter @Setter
public class UpdateCleanup {
    public enum State { KEPT, WAITING, BLOCKED, REQUESTED, REMOVED }
    @Id private String id = UUID.randomUUID().toString();
    @Column(nullable = false) private String jobId;
    @Column(nullable = false) private String downloadId;
    @Column(nullable = false) private Long eplId;
    @Column(nullable = false) private String hash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private State state;
    private String message;
    private Instant updatedAt;
}
