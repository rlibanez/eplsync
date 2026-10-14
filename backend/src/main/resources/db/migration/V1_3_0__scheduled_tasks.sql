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
