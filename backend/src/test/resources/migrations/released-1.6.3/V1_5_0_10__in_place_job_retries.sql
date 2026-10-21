CREATE TABLE job_retry_attempts (
 id varchar(36) PRIMARY KEY NOT NULL,
 kind varchar(16) NOT NULL CHECK(kind IN ('downloads','calibre')),
 job_id varchar(36) NOT NULL,
 owner_id varchar(36) REFERENCES users(id) ON DELETE SET NULL,
 username varchar(64) NOT NULL,
 created_at bigint NOT NULL,
 finished_at bigint,
 result varchar(24),
 before_job TEXT NOT NULL CHECK(json_valid(before_job))
);
CREATE INDEX idx_retry_attempt_job ON job_retry_attempts(kind,job_id,created_at,id);
CREATE TABLE job_retry_attempt_items (
 attempt_id varchar(36) NOT NULL REFERENCES job_retry_attempts(id) ON DELETE CASCADE,
 item_id varchar(36) NOT NULL,
 position integer NOT NULL,
 phase varchar(16) NOT NULL CHECK(phase IN ('SEND','VERIFY','WAIT','CLEANUP')),
 before_item TEXT NOT NULL CHECK(json_valid(before_item)),
 after_item TEXT CHECK(after_item IS NULL OR json_valid(after_item)),
 PRIMARY KEY(attempt_id,item_id)
);
