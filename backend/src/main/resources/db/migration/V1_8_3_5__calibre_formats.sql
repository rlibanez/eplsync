ALTER TABLE calibre_books ADD COLUMN formats TEXT CHECK(formats IS NULL OR json_valid(formats));
