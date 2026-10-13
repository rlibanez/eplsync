CREATE TABLE calibre_jobs (
    id varchar(36) PRIMARY KEY NOT NULL,
    created_at varchar(40) NOT NULL,
    updated_at varchar(40) NOT NULL,
    owner_id varchar(36),
    owner_username varchar(64) NOT NULL,
    destination varchar(64) NOT NULL,
    library varchar(256) NOT NULL,
    source varchar(64) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('QUEUED','RUNNING','PAUSED','COMPLETED','CANCELLED')),
    message varchar(1024)
);
CREATE TABLE calibre_job_items (
    id varchar(36) PRIMARY KEY NOT NULL,
    job_id varchar(36) NOT NULL REFERENCES calibre_jobs(id) ON DELETE CASCADE,
    position integer NOT NULL CHECK(position>=0),
    record_id varchar(64) NOT NULL,
    file_index integer NOT NULL CHECK(file_index>=0),
    name varchar(4096) NOT NULL,
    size bigint NOT NULL CHECK(size>0 AND size<=268435456),
    epl_id bigint NOT NULL CHECK(epl_id>0),
    revision varchar(40) NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('PENDING','SENDING','ACCEPTED','SKIPPED','FAILED','UNCERTAIN','CANCELLED')),
    calibre_id bigint CHECK(calibre_id IS NULL OR calibre_id>0),
    message varchar(1024),
    UNIQUE(job_id,record_id,file_index),
    UNIQUE(job_id,position)
);
CREATE INDEX idx_calibre_jobs_queue ON calibre_jobs(state,created_at,id);
CREATE INDEX idx_calibre_items_queue ON calibre_job_items(job_id,state,position);
CREATE INDEX idx_calibre_items_identity ON calibre_job_items(epl_id,revision,state);
