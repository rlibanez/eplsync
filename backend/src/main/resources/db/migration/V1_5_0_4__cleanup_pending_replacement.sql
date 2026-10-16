-- Keep the explicit opt-in with the frozen preview, including after restarts.
ALTER TABLE revision_cleanup_jobs ADD COLUMN allow_undownloaded boolean NOT NULL DEFAULT false CHECK(allow_undownloaded IN (0,1));
