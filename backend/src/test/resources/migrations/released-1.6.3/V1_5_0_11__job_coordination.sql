ALTER TABLE calibre_job_items ADD COLUMN transient_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE calibre_jobs ADD COLUMN last_served_at bigint NOT NULL DEFAULT 0;
CREATE INDEX idx_calibre_jobs_fair_queue ON calibre_jobs(state,next_check,last_served_at,created_at,id);
