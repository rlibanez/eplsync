PRAGMA defer_foreign_keys = ON;
CREATE TABLE torrent_bulk_jobs_new (
    batch_size integer not null,
    concurrency integer not null,
    created_at timestamp,
    interval_millis bigint not null,
    retry_at timestamp,
    selected_books bigint not null CHECK(typeof(selected_books)='integer' AND selected_books >= 0),
    updated_at timestamp,
    client varchar(255),
    event_actor_id varchar(255),
    event_actor_kind varchar(255),
    event_actor_username varchar(255),
    event_origin varchar(255),
    id varchar(255) not null CHECK(length(trim(id)) > 0),
    message varchar(255),
    multiple_hashes varchar(255) check ((multiple_hashes in ('ALL','SKIP','FIRST'))),
    previous_versions varchar(255) check ((previous_versions in ('KEEP','REMOVE_TORRENT','REMOVE_TORRENT_AND_FILES'))),
    state varchar(255) not null check ((state in ('QUEUED','RUNNING','RETRY_WAIT','PAUSED','DOWNLOAD_WAIT','CLEANUP_WAIT','COMPLETED','CANCELLED'))),
    target_fingerprint varchar(255),
    type varchar(255) check ((type in ('DOWNLOAD','UPDATE'))),
    primary key (id)
);
INSERT INTO torrent_bulk_jobs_new SELECT * FROM torrent_bulk_jobs;
DROP TABLE torrent_bulk_jobs;
ALTER TABLE torrent_bulk_jobs_new RENAME TO torrent_bulk_jobs;
CREATE INDEX idx_bulk_job_retention ON torrent_bulk_jobs(state,updated_at);
-- Verify restored references before clearing SQLite's deferred DROP TABLE violations.
CREATE TEMP TABLE bulk_job_fk_check (violations integer NOT NULL CHECK(violations=0));
INSERT INTO bulk_job_fk_check SELECT count(*) FROM pragma_foreign_key_check;
DROP TABLE bulk_job_fk_check;
PRAGMA defer_foreign_keys = OFF;

-- Closed historical submissions remain closed. Active jobs acquire lifecycle tracking.
ALTER TABLE torrent_bulk_jobs ADD COLUMN track_downloads boolean NOT NULL DEFAULT false CHECK(track_downloads IN (0,1));
ALTER TABLE torrent_bulk_jobs ADD COLUMN next_download_check bigint NOT NULL DEFAULT 0;
UPDATE torrent_bulk_jobs SET track_downloads=1 WHERE state NOT IN ('COMPLETED','CANCELLED');
ALTER TABLE torrent_bulk_items ADD COLUMN download_state varchar(32) CHECK(download_state IS NULL OR download_state IN ('SUBMITTED','ALREADY_EXISTS','UNKNOWN','QUEUED','DOWNLOADING','PAUSED','CHECKING','DOWNLOADED','ERROR','NOT_FOUND','IGNORED'));
ALTER TABLE torrent_bulk_items ADD COLUMN download_message TEXT;
ALTER TABLE torrent_bulk_items ADD COLUMN download_checked_at timestamp;
CREATE INDEX idx_bulk_download_check ON torrent_bulk_jobs(track_downloads,state,next_download_check);
