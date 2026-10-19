ALTER TABLE revision_cleanup_items ADD COLUMN preview_checked boolean NOT NULL DEFAULT true;
ALTER TABLE revision_cleanup_items ADD COLUMN preview_selected boolean NOT NULL DEFAULT false;
CREATE INDEX idx_cleanup_preview_checks ON revision_cleanup_items(job_id,preview_checked,position);
CREATE TABLE calibre_cleanup_preparations (
 job_id TEXT PRIMARY KEY REFERENCES revision_cleanup_jobs(id) ON DELETE CASCADE,
 input TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','READY','FAILED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1,
 last_epl bigint NOT NULL DEFAULT 0,
 rebuild_epl bigint,
 initialized boolean NOT NULL DEFAULT false,
 total bigint NOT NULL DEFAULT 0,
 processed bigint NOT NULL DEFAULT 0,
 message TEXT
);
CREATE TABLE calibre_cleanup_sources (
 job_id TEXT NOT NULL REFERENCES revision_cleanup_jobs(id) ON DELETE CASCADE,
 calibre_id bigint NOT NULL,
 epl_id bigint NOT NULL,
 revision TEXT NOT NULL,
 title TEXT NOT NULL,
 chosen boolean NOT NULL,
 PRIMARY KEY(job_id,calibre_id)
);
CREATE INDEX idx_calibre_cleanup_source_books ON calibre_cleanup_sources(job_id,epl_id,calibre_id);
CREATE TABLE calibre_cleanup_groups (
 job_id TEXT NOT NULL REFERENCES revision_cleanup_jobs(id) ON DELETE CASCADE,
 group_key TEXT NOT NULL,
 epl_id bigint NOT NULL,
 revision TEXT NOT NULL,
 retained_id TEXT NOT NULL,
 requires_choice boolean NOT NULL,
 formats_checked boolean NOT NULL DEFAULT false,
 PRIMARY KEY(job_id,group_key)
);
CREATE TABLE calibre_cleanup_copies (
 job_id TEXT NOT NULL,
 group_key TEXT NOT NULL,
 calibre_id TEXT NOT NULL,
 title TEXT NOT NULL,
 formats TEXT NOT NULL,
 formats_checked boolean NOT NULL DEFAULT false,
 PRIMARY KEY(job_id,group_key,calibre_id),
 FOREIGN KEY(job_id,group_key) REFERENCES calibre_cleanup_groups(job_id,group_key) ON DELETE CASCADE
);
CREATE INDEX idx_calibre_cleanup_copy_checks ON calibre_cleanup_copies(job_id,formats_checked,group_key,calibre_id);
CREATE INDEX idx_calibre_cleanup_group_checks ON calibre_cleanup_groups(job_id,formats_checked);
