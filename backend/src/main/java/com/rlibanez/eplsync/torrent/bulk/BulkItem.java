package com.rlibanez.eplsync.torrent.bulk;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "torrent_bulk_items", indexes = {
    @Index(name = "idx_bulk_item_queue", columnList = "jobId,state,position"),
    @Index(name = "idx_bulk_item_book", columnList = "jobId,eplId,state"),
    @Index(name = "idx_bulk_item_hash", columnList = "jobId,hash,state")
}, uniqueConstraints = @UniqueConstraint(columnNames = {"jobId", "position"}))
@Getter @Setter
public class BulkItem {
    @Id private String id;
    private String jobId;
    private long position;
    private Long eplId;
    private String hash;
    @Enumerated(EnumType.STRING) private State state;
    public enum State { PENDING, IN_FLIGHT, ACCEPTED, ALREADY_EXISTS, SKIPPED, FAILED, CANCELLED }
    private int attempts;
    private String message;
    @Column(columnDefinition = "TEXT") private String commandJson;
}
