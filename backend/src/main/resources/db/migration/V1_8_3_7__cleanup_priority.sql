ALTER TABLE revision_cleanup_jobs ADD COLUMN priority_requested boolean NOT NULL DEFAULT 0 CHECK(priority_requested IN (0,1));
