package com.rlibanez.eplsync.model;

import jakarta.persistence.*;

/** Read model of the locally synchronized library, independent of catalog imports. */
@Entity
@Table(name = "calibre_books")
public class CalibreBook {
    @Id private String id;
    private String generation;
    private boolean active;
    @Column(name="calibre_id") private Long calibreId;
    @Column(name="epl_id") private Long eplId;
    private Double revision;
    protected CalibreBook() {}
}
