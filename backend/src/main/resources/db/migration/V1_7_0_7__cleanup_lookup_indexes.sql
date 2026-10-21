-- Resolve the bounded deletion batch without scanning every download for the client.
CREATE INDEX idx_download_client_hash ON torrent_downloads(client_instance_id,hash);
-- Local history deletion checks pending requests by record, independently of their job.
CREATE INDEX idx_cleanup_download_state ON torrent_update_cleanup(download_id,state);
