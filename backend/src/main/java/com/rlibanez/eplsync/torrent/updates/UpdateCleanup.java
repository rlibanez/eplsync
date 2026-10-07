package com.rlibanez.eplsync.torrent.updates;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "torrent_update_cleanup", uniqueConstraints = @UniqueConstraint(columnNames = {"jobId", "downloadId"}), indexes = {
    @Index(name="idx_cleanup_client_state_hash",columnList="clientInstanceId,state,hash"),
    @Index(name="idx_cleanup_state_checked",columnList="state,lastCheckedAt,createdAt")})
@Getter @Setter
public class UpdateCleanup {
    public enum State { KEPT, WAITING, BLOCKED, REQUESTED, REMOVED, CANCELLED }
    @Id private String id = UUID.randomUUID().toString();
    private String jobId;
    @Column(nullable = false) private String downloadId;
    @Column(nullable = false) private Long eplId;
    @Column(nullable = false) private String hash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private State state;
    private String message;
    private Instant updatedAt;
    private Instant createdAt;
    private Instant lastCheckedAt;
    private String clientInstanceId;
    @Enumerated(EnumType.STRING) private PreviousVersions previousVersions;
    @Column(columnDefinition = "TEXT") private String targetHashes;
    private String actorId;
    private String actorUsername;
    private String actorKind;
    private Boolean automatic;
    private Boolean immediate;

    public void initialize(UpdatePlan plan, java.util.List<String> targets, com.rlibanez.eplsync.events.EventContext.Actor actor) {
        clientInstanceId = plan.getClientInstanceId(); previousVersions = plan.getPreviousVersions();
        targetHashes = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(targets);
        createdAt = plan.getCreatedAt(); automatic = Boolean.TRUE.equals(plan.getAutomaticCleanup()); immediate = false;
        actorId = actor.id(); actorUsername = actor.username(); actorKind = actor.kind();
    }
}
