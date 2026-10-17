-- Direct deletions share the durable cleanup worker, without requiring a replacement revision.
ALTER TABLE revision_cleanup_jobs ADD COLUMN direct_removal boolean NOT NULL DEFAULT false CHECK(direct_removal IN (0,1));
