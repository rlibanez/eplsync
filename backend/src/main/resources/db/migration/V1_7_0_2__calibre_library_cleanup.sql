ALTER TABLE revision_cleanup_jobs ADD COLUMN library_plan TEXT;
ALTER TABLE revision_cleanup_items ADD COLUMN cleanup_reason TEXT CHECK(cleanup_reason IS NULL OR cleanup_reason IN ('OLD_REVISION','DUPLICATE'));
ALTER TABLE revision_cleanup_items ADD COLUMN retained_identity TEXT;
ALTER TABLE revision_cleanup_items ADD COLUMN retained_formats TEXT;
