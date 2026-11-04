-- A partially prepared job must never be dispatched or resumed.
ALTER TABLE torrent_bulk_jobs ADD COLUMN preparing boolean NOT NULL DEFAULT 0 CHECK(preparing IN (0,1));
