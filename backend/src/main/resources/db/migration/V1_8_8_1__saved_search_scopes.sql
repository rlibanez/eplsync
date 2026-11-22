-- Preserve existing searches and classify discovery configurations by their source.
CREATE TABLE catalog_saved_searches_scoped (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope TEXT NOT NULL CHECK(scope IN ('CATALOG','DISCOVERY')),
    name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 120),
    filters TEXT NOT NULL,
    UNIQUE(user_id, scope, name)
);
INSERT INTO catalog_saved_searches_scoped(id,user_id,scope,name,filters)
SELECT id,user_id,
    CASE WHEN json_extract(filters,'$.discoverySource[0]') IN ('DOWNLOADS','CALIBRE','BOTH')
         THEN 'DISCOVERY' ELSE 'CATALOG' END,
    name,filters
FROM catalog_saved_searches;
DROP TABLE catalog_saved_searches;
ALTER TABLE catalog_saved_searches_scoped RENAME TO catalog_saved_searches;
CREATE INDEX idx_catalog_saved_searches_user ON catalog_saved_searches(user_id,scope);
