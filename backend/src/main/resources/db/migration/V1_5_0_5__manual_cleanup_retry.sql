-- An explicit retry is idempotent even if its HTTP response is lost or the app restarts.
CREATE TABLE revision_cleanup_retries (
 request_id varchar(64) PRIMARY KEY NOT NULL,
 job_id varchar(36) NOT NULL REFERENCES revision_cleanup_jobs(id) ON DELETE CASCADE,
 created_at bigint NOT NULL
);
CREATE INDEX idx_revision_cleanup_retries_job ON revision_cleanup_retries(job_id);
