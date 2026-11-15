ALTER TABLE calibre_job_items ADD COLUMN torrent_busy_attempts INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_calibre_items_next_check ON calibre_job_items(job_id, next_check)
WHERE state IN ('PENDING','UNCERTAIN','WAITING_DOWNLOAD','CLEANUP_PENDING');
