CREATE TABLE calibre_preparation_operations (
 id TEXT PRIMARY KEY,
 owner_id TEXT NOT NULL,
 owner_username TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('PREPARE','SEND')),
 state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
 phase TEXT NOT NULL,
 processed bigint NOT NULL DEFAULT 0,
 total bigint NOT NULL DEFAULT 0,
 cursor bigint NOT NULL DEFAULT 0,
 context TEXT NOT NULL,
 input TEXT NOT NULL,
 signature TEXT NOT NULL,
 result TEXT,
 message TEXT,
 calibre_job_id TEXT,
 download_job_id TEXT,
 created_at bigint NOT NULL,
 updated_at bigint NOT NULL
);
CREATE INDEX idx_calibre_preparation_queue ON calibre_preparation_operations(state,created_at,id);
CREATE INDEX idx_calibre_preparation_download ON calibre_preparation_operations(download_job_id) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX idx_calibre_preparation_calibre ON calibre_preparation_operations(calibre_job_id) WHERE state IN ('QUEUED','RUNNING');
