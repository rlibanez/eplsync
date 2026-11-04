ALTER TABLE torrent_downloads ADD COLUMN inventory_state TEXT NOT NULL DEFAULT 'PENDING' CHECK(inventory_state IN ('PENDING','CHECKED','ERROR'));
ALTER TABLE torrent_downloads ADD COLUMN inventory_checked_at TEXT;
ALTER TABLE torrent_downloads ADD COLUMN inventory_error TEXT;
ALTER TABLE torrent_downloads ADD COLUMN inventory_next_check BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_download_inventory_queue ON torrent_downloads(client_instance_id,inventory_state,inventory_next_check);
CREATE TABLE torrent_download_files (
 id TEXT PRIMARY KEY,
 download_id TEXT NOT NULL REFERENCES torrent_downloads(id) ON DELETE CASCADE,
 file_index INTEGER NOT NULL CHECK(file_index>=0),
 relative_path TEXT NOT NULL,
 size BIGINT NOT NULL CHECK(size>=0),
 extension TEXT NOT NULL,
 UNIQUE(download_id,file_index)
);
CREATE INDEX idx_download_file_extension ON torrent_download_files(extension,download_id);
ALTER TABLE calibre_job_items ADD COLUMN allowed_formats TEXT NOT NULL DEFAULT '["epub"]' CHECK(json_valid(allowed_formats));
ALTER TABLE calibre_job_items ADD COLUMN file_sha256 TEXT;
