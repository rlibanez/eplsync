-- Keep a user omission distinct from an existing Calibre copy; preserve all items and provenance.
CREATE TABLE calibre_job_items_new (
    id varchar(36) PRIMARY KEY NOT NULL,
    job_id varchar(36) NOT NULL REFERENCES calibre_jobs(id) ON DELETE CASCADE,
    position integer NOT NULL CHECK(position>=0),
    record_id varchar(64),
    file_index integer CHECK(file_index>=0),
    name varchar(4096) NOT NULL,
    size bigint CHECK(size>0 AND size<=268435456),
    epl_id bigint NOT NULL CHECK(epl_id>0),
    revision varchar(40) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('PENDING','SENDING','ACCEPTED','SKIPPED','FAILED','UNCERTAIN','CANCELLED','OMITTED','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','CLEANUP_PENDING')),
    next_check bigint NOT NULL DEFAULT 0,
    wait_until bigint,
    download_job_id varchar(36),
    completion_state varchar(16) NOT NULL DEFAULT 'ACCEPTED' CHECK(completion_state IN ('ACCEPTED','SKIPPED')),
    cleanup_policy varchar(16) NOT NULL DEFAULT 'KEEP' CHECK(cleanup_policy IN ('KEEP','EPUB','BOOK')),
    cleanup_ids TEXT NOT NULL DEFAULT '[]' CHECK(length(cleanup_ids)<=20000),
    identity_complete integer NOT NULL DEFAULT 0 CHECK(identity_complete IN (0,1)),
    calibre_id bigint CHECK(calibre_id IS NULL OR calibre_id>0),
    message varchar(1024),
    requested_cleanup_policy varchar(16),
    requested_cleanup_ids TEXT,
    cleanup_cancelled boolean NOT NULL DEFAULT false,
    epub_identity TEXT,
    transient_attempts integer NOT NULL DEFAULT 0,
    calibre_uuid varchar(64),
    identifiers_completed boolean NOT NULL DEFAULT false CHECK(identifiers_completed IN (0,1)),
    identifier_completion_pending boolean NOT NULL DEFAULT false CHECK(identifier_completion_pending IN (0,1)),
    cancelled_by_download_job_id varchar(36),
    CHECK(state IN ('WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','CANCELLED','OMITTED') OR (state='FAILED' AND wait_until IS NOT NULL AND (record_id IS NOT NULL OR download_job_id IS NOT NULL)) OR (record_id IS NOT NULL AND file_index IS NOT NULL AND size IS NOT NULL)),
    UNIQUE(job_id,record_id,file_index),
    UNIQUE(job_id,position)
);
INSERT INTO calibre_job_items_new (id, job_id, position, record_id, file_index, name, size, epl_id, revision, state, next_check, wait_until, download_job_id, completion_state, cleanup_policy, cleanup_ids, identity_complete, calibre_id, message, requested_cleanup_policy, requested_cleanup_ids, cleanup_cancelled, epub_identity, transient_attempts, calibre_uuid, identifiers_completed, identifier_completion_pending, cancelled_by_download_job_id)
SELECT id, job_id, position, record_id, file_index, name, size, epl_id, revision, state, next_check, wait_until, download_job_id, completion_state, cleanup_policy, cleanup_ids, identity_complete, calibre_id, message, requested_cleanup_policy, requested_cleanup_ids, cleanup_cancelled, epub_identity, transient_attempts, calibre_uuid, identifiers_completed, identifier_completion_pending, cancelled_by_download_job_id FROM calibre_job_items;
DROP TABLE calibre_job_items;
ALTER TABLE calibre_job_items_new RENAME TO calibre_job_items;
CREATE INDEX idx_calibre_items_queue ON calibre_job_items(job_id,state,position);
CREATE INDEX idx_calibre_items_identity ON calibre_job_items(epl_id,revision,state);
CREATE INDEX idx_calibre_items_download ON calibre_job_items(download_job_id,state);
CREATE INDEX idx_calibre_items_download_job ON calibre_job_items(download_job_id,job_id);
