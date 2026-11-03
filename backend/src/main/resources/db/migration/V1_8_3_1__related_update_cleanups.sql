-- A cleanup owns its lifecycle while retaining the original update's queue provenance.
ALTER TABLE revision_cleanup_jobs ADD COLUMN update_job_id varchar(36) REFERENCES torrent_bulk_jobs(id);
ALTER TABLE revision_cleanup_jobs ADD COLUMN next_check bigint NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uq_cleanup_update_job ON revision_cleanup_jobs(update_job_id) WHERE update_job_id IS NOT NULL;
