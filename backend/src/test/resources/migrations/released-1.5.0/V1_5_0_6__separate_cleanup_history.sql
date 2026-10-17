-- Preserve the explicit behavior of existing manual jobs; new previews persist their own choice.
ALTER TABLE revision_cleanup_jobs ADD COLUMN delete_history boolean NOT NULL DEFAULT true;
ALTER TABLE torrent_update_plans ADD COLUMN delete_history boolean NOT NULL DEFAULT false;
ALTER TABLE torrent_update_cleanup ADD COLUMN delete_history boolean NOT NULL DEFAULT false;
