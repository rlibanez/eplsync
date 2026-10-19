CREATE TABLE catalog_saved_searches (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 120),
    filters TEXT NOT NULL,
    UNIQUE(user_id, name)
);
CREATE INDEX idx_catalog_saved_searches_user ON catalog_saved_searches(user_id);
