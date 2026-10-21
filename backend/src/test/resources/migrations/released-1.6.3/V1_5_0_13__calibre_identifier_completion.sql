-- Keep the original EPUB identity unchanged; distinguish library metadata completion.
ALTER TABLE calibre_job_items ADD COLUMN calibre_uuid varchar(64);
ALTER TABLE calibre_job_items ADD COLUMN identifiers_completed boolean NOT NULL DEFAULT false CHECK(identifiers_completed IN (0,1));
ALTER TABLE calibre_job_items ADD COLUMN identifier_completion_pending boolean NOT NULL DEFAULT false CHECK(identifier_completion_pending IN (0,1));
