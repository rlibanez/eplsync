package com.rlibanez.eplsync.torrent.downloads;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "torrent_downloads", uniqueConstraints = @UniqueConstraint(
        name = "uk_download_identity", columnNames = {"client_instance_id", "epl_id", "hash"}), indexes = {
        @Index(name = "idx_download_book", columnList = "epl_id"),
        @Index(name = "idx_download_instance_status", columnList = "client_instance_id,status"),
        @Index(name = "idx_download_created", columnList = "created_at")})
@Getter @Setter
public class DownloadRecord {
    public enum Origin { EPLSYNC, DISCOVERED }
    @Id private String id = UUID.randomUUID().toString();
    @Column(nullable = false) private Long eplId;
    @Column(nullable = false) private Double revision;
    @Column(nullable = false, length = 64) private String hash;
    @Column(nullable = false) private String client;
    @Column(nullable = false, length = 64) private String clientInstanceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DownloadStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Origin origin;
    @Column(nullable = false) private Instant createdAt;
    private Instant requestedAt;
    private Instant submittedAt;
    private Instant discoveredAt;
    private Instant completedAt;
    private Instant lastCheckedAt;
    private Instant lastSeenAt;
    private String lastError;
}
