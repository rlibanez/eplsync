-- Keep previously applied development migrations intact.
PRAGMA defer_foreign_keys = ON;
CREATE TABLE scheduled_tasks_new (
 id varchar(36) PRIMARY KEY NOT NULL,
 name varchar(120) NOT NULL CHECK(length(name) BETWEEN 1 AND 120),
 operation varchar(32) NOT NULL CHECK(operation IN ('TORRENT_SYNC','CALIBRE_SYNC','CATALOG_IMPORT','TORRENT_UPDATE_SEARCH','CALIBRE_UPDATE_SEARCH','TORRENT_AUTO_UPDATE','CALIBRE_AUTO_UPDATE')),
 cron varchar(256) NOT NULL CHECK(length(cron) BETWEEN 1 AND 256),
 timezone varchar(128) NOT NULL CHECK(length(timezone) BETWEEN 1 AND 128),
 owner_id varchar(36) REFERENCES users(id) ON DELETE SET NULL,
 owner_username varchar(64) NOT NULL,
 enabled integer NOT NULL CHECK(enabled IN (0,1)),
 running integer NOT NULL DEFAULT 0 CHECK(running IN (0,1)),
 next_run bigint, last_run bigint, last_finished bigint,
 last_result varchar(16) CHECK(last_result IS NULL OR last_result IN ('RUNNING','SUCCEEDED','FAILED','INTERRUPTED','PARTIAL')),
 last_message varchar(1024), created_at bigint NOT NULL, updated_at bigint NOT NULL,
 CHECK(enabled=0 OR next_run IS NOT NULL)
);
INSERT INTO scheduled_tasks_new SELECT * FROM scheduled_tasks;
DROP TABLE scheduled_tasks;
ALTER TABLE scheduled_tasks_new RENAME TO scheduled_tasks;
CREATE INDEX idx_scheduled_tasks_due ON scheduled_tasks(enabled,running,next_run);
CREATE INDEX idx_scheduled_tasks_operation ON scheduled_tasks(operation,running);
PRAGMA defer_foreign_keys = OFF;
CREATE TABLE scheduled_automation_settings (
 task_id varchar(36) PRIMARY KEY REFERENCES scheduled_tasks(id) ON DELETE CASCADE,
 configuration TEXT NOT NULL CHECK(json_valid(configuration) AND length(CAST(configuration AS BLOB))<=65536)
);
CREATE TABLE scheduled_automation_runs (
 id varchar(36) PRIMARY KEY,
 task_id varchar(36) NOT NULL REFERENCES scheduled_tasks(id) ON DELETE CASCADE,
 owner_id varchar(36) REFERENCES users(id) ON DELETE SET NULL,
 state varchar(16) NOT NULL CHECK(state IN ('WAITING','ATTENTION','COMPLETED')),
 created_at bigint NOT NULL, finished_at bigint,
 download_job_id varchar(36), calibre_job_id varchar(36),
 attempts integer NOT NULL DEFAULT 0,
 message TEXT,
 configuration TEXT NOT NULL CHECK(json_valid(configuration)),
 context TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_automation_active ON scheduled_automation_runs(task_id) WHERE state IN ('WAITING','ATTENTION');
CREATE INDEX idx_automation_jobs ON scheduled_automation_runs(download_job_id,calibre_job_id);
CREATE TABLE scheduled_automation_jobs (
 run_id varchar(36) NOT NULL REFERENCES scheduled_automation_runs(id) ON DELETE CASCADE,
 kind varchar(16) NOT NULL CHECK(kind IN ('downloads','calibre')),
 job_id varchar(36) NOT NULL,
 PRIMARY KEY(kind,job_id)
);
