CREATE TABLE revision_cleanup_jobs (
 id varchar(36) PRIMARY KEY NOT NULL,
 source varchar(16) NOT NULL CHECK(source IN ('downloads','calibre')),
 action varchar(16) NOT NULL CHECK(action IN ('RECORD','TORRENT','FILES','EPUB','BOOK')),
 owner_id varchar(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 owner_username varchar(64) NOT NULL,
 context TEXT NOT NULL,
 library varchar(256),
 state varchar(16) NOT NULL CHECK(state IN ('PREVIEW','QUEUED','RUNNING','PAUSED','COMPLETED','CANCELLED')),
 result varchar(16),
 message TEXT,
 confirmation TEXT,
 reported_signature TEXT,
 cancel_requested boolean NOT NULL DEFAULT false,
 created_at bigint NOT NULL,
 updated_at bigint NOT NULL,
 expires_at bigint NOT NULL
);
CREATE INDEX idx_revision_cleanup_owner ON revision_cleanup_jobs(owner_id,created_at);
CREATE INDEX idx_revision_cleanup_state ON revision_cleanup_jobs(state,created_at);
CREATE TABLE revision_cleanup_items (
 id varchar(36) PRIMARY KEY NOT NULL,
 job_id varchar(36) NOT NULL REFERENCES revision_cleanup_jobs(id) ON DELETE CASCADE,
 position integer NOT NULL,
 old_id varchar(64) NOT NULL,
 replacement_id varchar(64) NOT NULL,
 epl_id bigint NOT NULL CHECK(epl_id>0),
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
CREATE INDEX idx_revision_cleanup_items_job ON revision_cleanup_items(job_id,position);
CREATE INDEX idx_revision_cleanup_items_pending ON revision_cleanup_items(epl_id,state);
