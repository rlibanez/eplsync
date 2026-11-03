-- Keep previous failures when retrying an item in its original cleanup job.
CREATE TABLE revision_cleanup_failure_history (
 request_id varchar(64) NOT NULL REFERENCES revision_cleanup_retries(request_id) ON DELETE CASCADE,
 item_id varchar(36) NOT NULL REFERENCES revision_cleanup_items(id) ON DELETE CASCADE,
 job_id varchar(36) NOT NULL REFERENCES revision_cleanup_jobs(id) ON DELETE CASCADE,
 created_at bigint NOT NULL,
 username varchar(64) NOT NULL,
 title TEXT NOT NULL,
 message TEXT,
 PRIMARY KEY(request_id,item_id)
);
CREATE INDEX idx_cleanup_failure_history_job ON revision_cleanup_failure_history(job_id,created_at);
