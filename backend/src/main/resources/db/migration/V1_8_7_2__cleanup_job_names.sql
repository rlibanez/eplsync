ALTER TABLE revision_cleanup_jobs ADD COLUMN name TEXT CHECK(name IS NULL OR length(name)<=150);
