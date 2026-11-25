-- Distinguish new, explicitly authorized imports from the previous metadata-only correction.
ALTER TABLE calibre_revision_confirmations ADD COLUMN mode TEXT NOT NULL DEFAULT 'METADATA' CHECK(mode IN ('METADATA','IMPORT'));
ALTER TABLE calibre_revision_confirmations ADD COLUMN old_calibre_id INTEGER;
ALTER TABLE calibre_revision_confirmations ADD COLUMN new_calibre_id INTEGER;
ALTER TABLE calibre_revision_confirmations ADD COLUMN new_metadata TEXT CHECK(new_metadata IS NULL OR json_valid(new_metadata));
