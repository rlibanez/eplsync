-- Stage library pages separately; publish the complete snapshot atomically.
CREATE TABLE calibre_books (
    id varchar(100) PRIMARY KEY NOT NULL,
    generation varchar(36) NOT NULL,
    active boolean NOT NULL DEFAULT false,
    calibre_id bigint NOT NULL CHECK(typeof(calibre_id)='integer' AND calibre_id > 0),
    epl_id bigint CHECK(epl_id IS NULL OR (typeof(epl_id)='integer' AND epl_id > 0)),
    revision float CHECK(revision IS NULL OR (typeof(revision) IN ('integer','real') AND revision > 0 AND revision <= 1.7976931348623157e308)),
    title varchar(4096) NOT NULL DEFAULT '',
    authors varchar(16384) NOT NULL DEFAULT '',
    UNIQUE(generation, calibre_id)
);
CREATE INDEX idx_calibre_catalog ON calibre_books(active, epl_id, revision);
CREATE INDEX idx_calibre_title ON calibre_books(active, title COLLATE EPL_TEXT, calibre_id);
