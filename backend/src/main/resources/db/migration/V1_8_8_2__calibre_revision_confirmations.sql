-- Keep consent across an interrupted/uncertain remote metadata write.
CREATE TABLE calibre_revision_confirmations (
 item_id TEXT PRIMARY KEY REFERENCES calibre_job_items(id) ON DELETE CASCADE,
 created_at TEXT NOT NULL,
 applied_at TEXT,
 username TEXT NOT NULL,
 epub_revision TEXT NOT NULL,
 target_revision TEXT NOT NULL,
 fingerprint TEXT NOT NULL,
 expected_metadata TEXT NOT NULL CHECK(json_valid(expected_metadata))
);
