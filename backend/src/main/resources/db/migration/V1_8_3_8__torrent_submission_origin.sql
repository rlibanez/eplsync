-- Record dispatch intent before HTTP so recovery can distinguish our sends from pre-existing torrents.
-- Existing results have no reliable provenance and are deliberately left unchanged.
ALTER TABLE torrent_bulk_items ADD COLUMN submission_started BOOLEAN NOT NULL DEFAULT 0;
