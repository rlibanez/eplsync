ALTER TABLE calibre_books ADD COLUMN identifiers TEXT CHECK(identifiers IS NULL OR json_valid(identifiers));
