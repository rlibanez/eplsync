package com.rlibanez.eplsync.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Singleton describing the last applied CSV, not an import history. */
@Entity
@Table(name = "catalog_metadata")
@Getter @Setter @NoArgsConstructor
public class CatalogMetadata {
    @Id private Long id = 1L;
    private String sourceUrl;
    private String sourceFileName;
    // ZIP local timestamps have no reliable timezone; preserve the source representation.
    private String sourceModifiedAt;
    private Instant importedAt;
    private String importMode;
    private long totalRows;
    private long insertedRows;
    private long updatedRows;
    private long unchangedRows;
    private long errorRows;
    private long durationMs;
    private String sourceSha256;
}
