ALTER TABLE torrent_bulk_jobs ADD COLUMN name TEXT CHECK(name IS NULL OR length(name)<=150);
ALTER TABLE calibre_jobs ADD COLUMN name TEXT CHECK(name IS NULL OR length(name)<=150);
