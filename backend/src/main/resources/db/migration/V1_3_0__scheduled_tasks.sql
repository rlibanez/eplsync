CREATE TABLE scheduled_tasks (
    id varchar(36) PRIMARY KEY NOT NULL,
    name varchar(120) NOT NULL CHECK(length(name) BETWEEN 1 AND 120),
    operation varchar(32) NOT NULL CHECK(operation IN ('TORRENT_SYNC','CALIBRE_SYNC','CATALOG_IMPORT','TORRENT_UPDATE_SEARCH','CALIBRE_UPDATE_SEARCH')),
    cron varchar(256) NOT NULL CHECK(length(cron) BETWEEN 1 AND 256),
    timezone varchar(128) NOT NULL CHECK(length(timezone) BETWEEN 1 AND 128),
    owner_id varchar(36) REFERENCES users(id) ON DELETE SET NULL,
    owner_username varchar(64) NOT NULL,
    enabled integer NOT NULL CHECK(enabled IN (0,1)),
    running integer NOT NULL DEFAULT 0 CHECK(running IN (0,1)),
    next_run bigint,
    last_run bigint,
    last_finished bigint,
    last_result varchar(16) CHECK(last_result IS NULL OR last_result IN ('RUNNING','SUCCEEDED','FAILED','INTERRUPTED','PARTIAL')),
    last_message varchar(1024),
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    CHECK(enabled=0 OR next_run IS NOT NULL)
);
CREATE INDEX idx_scheduled_tasks_due ON scheduled_tasks(enabled,running,next_run);
CREATE INDEX idx_scheduled_tasks_operation ON scheduled_tasks(operation,running);

-- One complete result per user and search type; metadata remains queryable without parsing all rows.
CREATE TABLE update_search_results (
    user_id varchar(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    search_type varchar(16) NOT NULL CHECK(search_type IN ('downloads','calibre')),
    searched_at bigint NOT NULL CHECK(typeof(searched_at)='integer' AND searched_at>=0),
    origin varchar(16) NOT NULL CHECK(origin IN ('MANUAL','SCHEDULED','SYSTEM')),
    criteria_json TEXT NOT NULL CHECK(json_valid(criteria_json) AND json_type(criteria_json)='object'),
    results_json TEXT NOT NULL CHECK(json_valid(results_json) AND json_type(results_json)='array'),
    PRIMARY KEY(user_id,search_type)
);

-- Preserve legacy JSON results. Malformed or orphaned settings remain untouched for diagnosis.
INSERT INTO update_search_results(user_id,search_type,searched_at,origin,criteria_json,results_json)
SELECT u.id,
       CASE WHEN s.setting_key='tasks.update-search.downloads.'||u.id THEN 'downloads' ELSE 'calibre' END,
       json_extract(s.setting_value,'$.searchedAt'),
       coalesce(json_extract(s.setting_value,'$.origin'),'SCHEDULED'),
       CASE WHEN json_type(s.setting_value,'$.criteria')='object'
            THEN json_extract(s.setting_value,'$.criteria')
            WHEN s.setting_key='tasks.update-search.downloads.'||u.id
            THEN json_object('states',json(coalesce(json_extract(s.setting_value,'$.states'),'[]')),'synchronize',json('true'))
            ELSE json_object('updatesOnly',json('true')) END,
       json_extract(s.setting_value,'$.rows')
FROM app_settings s JOIN users u
  ON s.setting_key IN ('tasks.update-search.downloads.'||u.id,'tasks.update-search.calibre.'||u.id)
WHERE CASE WHEN json_valid(s.setting_value) THEN
    json_type(s.setting_value,'$.rows')='array'
    AND json_type(s.setting_value,'$.searchedAt')='integer'
    AND json_extract(s.setting_value,'$.searchedAt')>=0
    AND coalesce(json_extract(s.setting_value,'$.origin'),'SCHEDULED') IN ('MANUAL','SCHEDULED','SYSTEM')
    AND (json_type(s.setting_value,'$.states') IS NULL OR json_type(s.setting_value,'$.states')='array')
  ELSE false END;

DELETE FROM app_settings
WHERE EXISTS (
    SELECT 1 FROM update_search_results r
    WHERE app_settings.setting_key='tasks.update-search.'||r.search_type||'.'||r.user_id
);

-- Update jobs finish only after both submission and cleanup have finished.
-- Deferred child references remain attached to the original table name after replacement.
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
    state varchar(255) not null check ((state in ('QUEUED','RUNNING','RETRY_WAIT','PAUSED','CLEANUP_WAIT','COMPLETED','CANCELLED'))),
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
UPDATE torrent_bulk_jobs SET type='UPDATE'
WHERE EXISTS (SELECT 1 FROM torrent_update_plans p WHERE p.job_id=torrent_bulk_jobs.id);
UPDATE torrent_bulk_jobs SET state='CLEANUP_WAIT'
WHERE state='COMPLETED'
  AND EXISTS (SELECT 1 FROM torrent_update_cleanup c
              WHERE c.job_id=torrent_bulk_jobs.id AND c.state IN ('WAITING','BLOCKED','REQUESTED'));

-- Personal presentation preferences; event retention and read cursors remain independent.
ALTER TABLE users ADD COLUMN notification_preferences TEXT
    CHECK(notification_preferences IS NULL OR (
        json_valid(notification_preferences) AND json_type(notification_preferences)='object'
        AND json_type(notification_preferences,'$.floating') IN ('true','false')
        AND json_type(notification_preferences,'$.unreadBadge') IN ('true','false')));
