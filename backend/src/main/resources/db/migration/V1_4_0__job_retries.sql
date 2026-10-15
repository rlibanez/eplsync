-- Retry provenance is independent of the source jobs: their history is never rewritten.
CREATE TABLE job_retry_previews (
    id varchar(36) PRIMARY KEY NOT NULL,
    owner_id varchar(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind varchar(16) NOT NULL CHECK(kind IN ('downloads','calibre')),
    parent_id varchar(36) NOT NULL,
    payload TEXT NOT NULL CHECK(json_valid(payload) AND length(CAST(payload AS BLOB))<=8388608),
    created_at bigint NOT NULL,
    expires_at bigint NOT NULL
);
CREATE INDEX idx_retry_previews_expiry ON job_retry_previews(expires_at);
CREATE TABLE job_retry_jobs (
    kind varchar(16) NOT NULL CHECK(kind IN ('downloads','calibre')),
    child_id varchar(36) NOT NULL,
    parent_id varchar(36) NOT NULL,
    root_id varchar(36) NOT NULL,
    request_id varchar(36) NOT NULL UNIQUE,
    owner_id varchar(36) REFERENCES users(id) ON DELETE SET NULL,
    created_at bigint NOT NULL,
    PRIMARY KEY(kind,child_id)
);
CREATE INDEX idx_retry_parent ON job_retry_jobs(kind,parent_id,created_at);
CREATE INDEX idx_retry_root ON job_retry_jobs(kind,root_id);
CREATE TABLE job_retry_items (
    kind varchar(16) NOT NULL,
    child_id varchar(36) NOT NULL,
    parent_id varchar(36) NOT NULL,
    source_item_id varchar(36) NOT NULL,
    child_item_id varchar(36) NOT NULL,
    phase varchar(16) NOT NULL CHECK(phase IN ('SEND','VERIFY','WAIT','CLEANUP')),
    PRIMARY KEY(kind,parent_id,source_item_id),
    FOREIGN KEY(kind,child_id) REFERENCES job_retry_jobs(kind,child_id) ON DELETE CASCADE
);
ALTER TABLE calibre_job_items ADD COLUMN requested_cleanup_policy varchar(16);
ALTER TABLE calibre_job_items ADD COLUMN requested_cleanup_ids TEXT;
ALTER TABLE calibre_job_items ADD COLUMN cleanup_cancelled boolean NOT NULL DEFAULT false;
UPDATE calibre_job_items SET requested_cleanup_policy=cleanup_policy,requested_cleanup_ids=cleanup_ids;
