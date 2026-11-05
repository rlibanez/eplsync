-- Keep uncertain submissions recoverable without pausing unrelated items.
ALTER TABLE torrent_bulk_items ADD COLUMN retry_after BIGINT NOT NULL DEFAULT 0;
ALTER TABLE torrent_bulk_items ADD COLUMN submission_until BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_bulk_item_retry ON torrent_bulk_items(job_id,state,retry_after,position);
CREATE INDEX idx_inventory_downloaded_queue ON torrent_downloads(client_instance_id,inventory_next_check,id) WHERE inventory_state<>'CHECKED' AND status='DOWNLOADED';
