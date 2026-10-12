package com.rlibanez.eplsync.model;

import jakarta.persistence.*;

/** Read model of the locally synchronized library, independent of catalog imports. */
@Entity
@Table(name = "calibre_books")
public class CalibreBook {
    @Id private String id;
    @Column(name="generation") private String generation;
    @Column(name="active") private boolean active;
    @Column(name="calibre_id") private Long calibreId;
    @Column(name="epl_id") private Long eplId;
    @Column(name="revision") private Double revision;
    @Column(length=4096) private String title;
    @Column(length=16384) private String authors;
    protected CalibreBook() {}
}
