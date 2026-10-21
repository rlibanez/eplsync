CREATE TABLE revision_cleanup_items_new (
 id varchar(36) PRIMARY KEY NOT NULL,
 job_id varchar(36) NOT NULL REFERENCES revision_cleanup_jobs(id) ON DELETE CASCADE,
 position integer NOT NULL,
 old_id varchar(64) NOT NULL,
 replacement_id varchar(64) NOT NULL,
 epl_id bigint CHECK(epl_id IS NULL OR epl_id>0),
 calibre_identity TEXT,
 title varchar(4096) NOT NULL,
 revision varchar(64) NOT NULL,
 replacement_revision varchar(64) NOT NULL,
 old_hash varchar(64),
 replacement_hash varchar(64),
 formats TEXT NOT NULL DEFAULT '[]',
 state varchar(24) NOT NULL CHECK(state IN ('READY','EXCLUDED','PENDING','RUNNING','VERIFYING','SUCCEEDED','BLOCKED','FAILED','UNCERTAIN','SKIPPED','CANCELLED')),
 message TEXT,
 dispatched boolean NOT NULL DEFAULT false,
 UNIQUE(job_id,old_id)
);
INSERT INTO revision_cleanup_items_new(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,old_hash,replacement_hash,formats,state,message,dispatched) SELECT id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,old_hash,replacement_hash,formats,state,message,dispatched FROM revision_cleanup_items;
DROP TABLE revision_cleanup_items;
ALTER TABLE revision_cleanup_items_new RENAME TO revision_cleanup_items;
CREATE INDEX idx_revision_cleanup_items_job ON revision_cleanup_items(job_id,position);
CREATE INDEX idx_revision_cleanup_items_pending ON revision_cleanup_items(epl_id,state);
