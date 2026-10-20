ALTER TABLE calibre_job_items ADD COLUMN cancelled_by_download_job_id varchar(36);
CREATE INDEX idx_calibre_items_download_job ON calibre_job_items(download_job_id,job_id);
