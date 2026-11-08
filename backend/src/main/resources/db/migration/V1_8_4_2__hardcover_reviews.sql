ALTER TABLE calibre_books ADD COLUMN calibre_uuid TEXT;
CREATE TABLE calibre_hardcover_reviews (
 destination TEXT NOT NULL, library TEXT NOT NULL, calibre_id INTEGER NOT NULL,
 calibre_uuid TEXT NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL,
 reviewed_at TEXT NOT NULL, reviewed_by TEXT NOT NULL,
 PRIMARY KEY(destination,library,calibre_id)
);
