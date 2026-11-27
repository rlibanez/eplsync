-- NULL means the server environment supplies the initial duration until an administrator saves it.
ALTER TABLE security_policy ADD COLUMN remember_me_days INTEGER CHECK(remember_me_days IS NULL OR remember_me_days BETWEEN 1 AND 365);
CREATE TABLE remembered_access (
 token_hash TEXT PRIMARY KEY CHECK(length(token_hash)=64),
 user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 security_version BIGINT NOT NULL,
 issued_at BIGINT NOT NULL,
 expires_at BIGINT NOT NULL CHECK(expires_at>issued_at)
);
CREATE INDEX idx_remembered_access_user ON remembered_access(user_id);
CREATE INDEX idx_remembered_access_expiry ON remembered_access(expires_at);
