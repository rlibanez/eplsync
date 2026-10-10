package com.rlibanez.eplsync.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.Instant;

import com.rlibanez.eplsync.model.enums.BookStatus;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.model.enums.PublicationStatus;

/**
 * Libro del catálogo ePubLibre importado desde CSV.
 */
@Entity
@Table(name = "catalog_books", indexes = {
        @Index(name = "idx_catalog_title", columnList = "title"),
        @Index(name = "idx_catalog_author", columnList = "author"),
        @Index(name = "idx_catalog_language", columnList = "language"),
        @Index(name = "idx_catalog_status", columnList = "status"),
        @Index(name = "idx_catalog_publication_year", columnList = "publication_year")
})
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CatalogBook {

    @Id
    @EqualsAndHashCode.Include
    @ToString.Include
    @Column(name = "epl_id", nullable = false, updatable = false)
    private Long eplId;

    @Column(name = "revision", nullable = false)
    @ToString.Include
    private Double revision;

    @Column(name = "author", nullable = false, length = 16384)
    @ToString.Include
    private String author;

    @Column(name = "title", nullable = false, length = 4096)
    @ToString.Include
    private String title;

    @Column(name = "genres", length = 4096)
    private String genres;

    @Column(name = "collection", length = 4096)
    private String collection;

    @Column(name = "volume")
    private Double volume;

    /** Año de publicación del libro original. */
    @Column(name = "publication_year")
    private Integer publicationYear;

    @Column(name = "synopsis", columnDefinition = "TEXT")
    private String synopsis;

    @Column(name = "pages")
    private Integer pages;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", length = 50)
    private Language language;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", length = 50)
    private PublicationStatus publicationStatus;

    @Column(name = "publication_date")
    private LocalDate publicationDate;

    @Column(name = "insert_date", updatable = false)
    private Instant insertDate;

    // SQLite stores Instant as epoch milliseconds. This read-only expression is
    // used for minute-level catalog ordering; the original timestamp stays intact.
    @org.hibernate.annotations.Formula("cast((insert_date - ((insert_date % 60000 + 60000) % 60000)) / 60000 as integer)")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long insertMinute;

    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    @PrePersist
    private void onCreate() {
        insertDate = Instant.now();
        lastModifiedDate = null;
    }

    @PreUpdate
    private void onUpdate() {
        lastModifiedDate = Instant.now();
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50)
    private BookStatus status;

    @Column(name = "rating")
    private Double rating;

    @Column(name = "votes_count")
    private Integer votesCount;

    @Column(name = "links", columnDefinition = "TEXT")
    private String links;

    @Column(name = "cover_url", columnDefinition = "TEXT")
    private String coverUrl;

    @Column(name = "cover_available")
    private Boolean coverAvailable;
}
