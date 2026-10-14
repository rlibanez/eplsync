CREATE TABLE calibre_jobs (
    id varchar(36) PRIMARY KEY NOT NULL,
    created_at varchar(40) NOT NULL,
    updated_at varchar(40) NOT NULL,
    owner_id varchar(36),
    owner_username varchar(64) NOT NULL,
    destination varchar(64) NOT NULL,
    library varchar(256) NOT NULL,
    source varchar(64) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('QUEUED','RUNNING','PAUSED','COMPLETED','CANCELLED')),
    next_probe bigint NOT NULL DEFAULT 0,
    next_check bigint NOT NULL DEFAULT 0,
    message varchar(1024)
);
CREATE TABLE calibre_job_items (
    id varchar(36) PRIMARY KEY NOT NULL,
    job_id varchar(36) NOT NULL REFERENCES calibre_jobs(id) ON DELETE CASCADE,
    position integer NOT NULL CHECK(position>=0),
    record_id varchar(64),
    file_index integer CHECK(file_index>=0),
    name varchar(4096) NOT NULL,
    size bigint CHECK(size>0 AND size<=268435456),
    epl_id bigint NOT NULL CHECK(epl_id>0),
    revision varchar(40) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('PENDING','SENDING','ACCEPTED','SKIPPED','FAILED','UNCERTAIN','CANCELLED','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','CLEANUP_PENDING')),
    next_check bigint NOT NULL DEFAULT 0,
    wait_until bigint,
    download_job_id varchar(36),
    completion_state varchar(16) NOT NULL DEFAULT 'ACCEPTED' CHECK(completion_state IN ('ACCEPTED','SKIPPED')),
    cleanup_policy varchar(16) NOT NULL DEFAULT 'KEEP' CHECK(cleanup_policy IN ('KEEP','EPUB','BOOK')),
    cleanup_ids TEXT NOT NULL DEFAULT '[]' CHECK(length(cleanup_ids)<=20000),
    identity_complete integer NOT NULL DEFAULT 0 CHECK(identity_complete IN (0,1)),
    calibre_id bigint CHECK(calibre_id IS NULL OR calibre_id>0),
    message varchar(1024),
    CHECK(state IN ('WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','CANCELLED') OR (record_id IS NOT NULL AND file_index IS NOT NULL AND size IS NOT NULL)),
    UNIQUE(job_id,record_id,file_index),
    UNIQUE(job_id,position)
);
CREATE INDEX idx_calibre_jobs_queue ON calibre_jobs(state,created_at,id);
CREATE INDEX idx_calibre_items_queue ON calibre_job_items(job_id,state,position);
CREATE INDEX idx_calibre_items_identity ON calibre_job_items(epl_id,revision,state);
-- Prevent a retried dialog submission from creating duplicate download/Calibre jobs.
CREATE TABLE calibre_update_requests (
    id varchar(64) PRIMARY KEY NOT NULL,
    owner_id varchar(36) NOT NULL,
    payload TEXT NOT NULL CHECK(length(payload)<=524288),
    result TEXT NOT NULL,
    created_at varchar(40) NOT NULL
);
CREATE INDEX idx_calibre_items_download ON calibre_job_items(download_job_id,state);
