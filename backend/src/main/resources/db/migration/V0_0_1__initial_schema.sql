-- Initial persistent schema for EPL Sync 0.0.1.
-- Consolidated before publication for fresh installations; published migrations are immutable.
-- Future schema changes after publication require a new migration.
CREATE TABLE app_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    category TEXT NOT NULL,
    action TEXT NOT NULL,
    outcome TEXT NOT NULL,
    origin TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    details TEXT NOT NULL,
    actor_id TEXT,
    actor_username TEXT,
    actor_kind TEXT
);

CREATE TABLE app_settings (
    setting_key varchar(255) not null,
    setting_value TEXT not null,
    primary key (setting_key)
);

CREATE TABLE catalog_books (
    cover_available boolean,
    pages integer,
    publication_date date,
    publication_year integer,
    rating float,
    revision float not null CHECK(typeof(revision) IN ('integer','real') AND revision > 0 AND revision <= 1.7976931348623157e308),
    volume float,
    votes_count integer,
    epl_id bigint not null CHECK(typeof(epl_id)='integer' AND epl_id > 0),
    insert_date timestamp,
    last_modified_date timestamp,
    language varchar(50) check ((language in ('ESPANOL','INGLES','CATALAN','GALLEGO','EUSKERA','FRANCES','ITALIANO','PORTUGUES','ALEMAN','ESPERANTO','SUECO','OTRO'))),
    publication_status varchar(50) check ((publication_status in ('PUBLISHED','UPDATED','UNKNOWN'))),
    status varchar(50) check ((status in ('DISPONIBLE','VERIFICADO','DESCONOCIDO'))),
    genres varchar(4096) CHECK(genres IS NULL OR (typeof(genres)='text' AND instr(genres,char(0))=0 AND length(genres) <= 4096)),
    title varchar(4096) not null CHECK(typeof(title)='text' AND length(title) <= 4096 AND length(trim(title,char(9,10,11,12,13,28,29,30,31,32,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288))) > 0 AND instr(title,char(0))=0),
    author varchar(16384) not null CHECK(typeof(author)='text' AND length(author) <= 16384 AND length(trim(author,char(9,10,11,12,13,28,29,30,31,32,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288))) > 0 AND instr(author,char(0))=0),
    collection varchar(4096) CHECK(collection IS NULL OR (typeof(collection)='text' AND instr(collection,char(0))=0 AND length(collection) <= 4096)),
    cover_url TEXT CHECK(cover_url IS NULL OR (typeof(cover_url)='text' AND instr(cover_url,char(0))=0 AND length(cover_url) <= 8192)),
    links TEXT CHECK(links IS NULL OR (typeof(links)='text' AND instr(links,char(0))=0 AND length(links) <= 65536)),
    synopsis TEXT CHECK(synopsis IS NULL OR (typeof(synopsis)='text' AND instr(synopsis,char(0))=0 AND length(synopsis) <= 524288)),
    primary key (epl_id)
);

CREATE TABLE catalog_metadata (
    duration_ms bigint not null,
    error_rows bigint not null,
    id bigint not null,
    imported_at timestamp,
    inserted_rows bigint not null,
    missing_rows bigint,
    total_rows bigint not null,
    unchanged_rows bigint not null,
    updated_rows bigint not null,
    import_mode varchar(255),
    source_archive_name varchar(255),
    source_file_name varchar(255),
    source_modified_at varchar(255),
    source_sha256 varchar(255),
    source_type varchar(255),
    source_url varchar(255),
    source_zip_sha256 varchar(255),
    primary key (id)
);

CREATE TABLE revision_update_settings (
    id INTEGER PRIMARY KEY CHECK(id=1),
    settings TEXT NOT NULL
);

CREATE TABLE security_policy (
    id INTEGER PRIMARY KEY CHECK(id=1),
    registration_enabled INTEGER NOT NULL DEFAULT 0,
    approval_required INTEGER NOT NULL DEFAULT 1,
    idle_minutes INTEGER NOT NULL DEFAULT 30,
    maximum_hours INTEGER NOT NULL DEFAULT 12,
    password_minimum_length INTEGER NOT NULL DEFAULT 8
);

CREATE TABLE torrent_bulk_items (
    attempts integer not null CHECK(typeof(attempts)='integer' AND attempts >= 0),
    revision float CHECK(revision IS NULL OR (typeof(revision) IN ('integer','real') AND revision > 0 AND revision <= 1.7976931348623157e308)),
    epl_id bigint CHECK(epl_id IS NULL OR (typeof(epl_id)='integer' AND epl_id > 0)),
    position bigint not null CHECK(typeof(position)='integer' AND position >= 0),
    command_json TEXT,
    hash varchar(255),
    id varchar(255) not null CHECK(length(trim(id)) > 0),
    job_id varchar(255) not null REFERENCES torrent_bulk_jobs(id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    message varchar(255),
    state varchar(255) not null check ((state in ('PENDING','IN_FLIGHT','ACCEPTED','ALREADY_EXISTS','SKIPPED','FAILED','CANCELLED'))),
    primary key (id)
);

CREATE TABLE torrent_bulk_jobs (
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
    state varchar(255) not null check ((state in ('QUEUED','RUNNING','RETRY_WAIT','PAUSED','COMPLETED','CANCELLED'))),
    target_fingerprint varchar(255),
    type varchar(255) check ((type in ('DOWNLOAD','UPDATE'))),
    primary key (id)
);

CREATE TABLE torrent_downloads (
    revision float not null CHECK(typeof(revision) IN ('integer','real') AND revision > 0 AND revision <= 1.7976931348623157e308),
    completed_at timestamp,
    created_at timestamp not null,
    discovered_at timestamp,
    epl_id bigint not null CHECK(typeof(epl_id)='integer' AND epl_id > 0),
    last_checked_at timestamp,
    last_seen_at timestamp,
    requested_at timestamp,
    submitted_at timestamp,
    client_instance_id varchar(64) not null,
    hash varchar(64) not null,
    client varchar(255) not null,
    id varchar(255) not null CHECK(length(trim(id)) > 0),
    last_error varchar(255),
    origin varchar(255) not null check ((origin in ('EPLSYNC','DISCOVERED'))),
    status varchar(255) not null check ((status in ('SUBMITTED','ALREADY_EXISTS','UNKNOWN','QUEUED','DOWNLOADING','PAUSED','CHECKING','DOWNLOADED','ERROR','NOT_FOUND'))),
    primary key (id)
);

CREATE TABLE torrent_update_cleanup (
    automatic boolean,
    immediate boolean,
    replacement_accepted boolean,
    created_at timestamp,
    epl_id bigint not null CHECK(typeof(epl_id)='integer' AND epl_id > 0),
    last_checked_at timestamp,
    updated_at timestamp,
    actor_id varchar(255),
    actor_kind varchar(255),
    actor_username varchar(255),
    client_instance_id varchar(255),
    download_id varchar(255) not null,
    hash varchar(255) not null,
    id varchar(255) not null CHECK(length(trim(id)) > 0),
    job_id varchar(255),
    message varchar(255),
    previous_versions varchar(255) check ((previous_versions in ('KEEP','REMOVE_TORRENT','REMOVE_TORRENT_AND_FILES'))),
    state varchar(255) not null check ((state in ('KEPT','WAITING','BLOCKED','REQUESTED','REMOVED','CANCELLED'))),
    target_hashes TEXT,
    primary key (id)
);

CREATE TABLE torrent_update_plans (
    automatic_cleanup boolean,
    created_at timestamp not null,
    cleanup_timing varchar(255) check ((cleanup_timing in ('IMMEDIATE','AFTER_DOWNLOAD'))),
    client_instance_id varchar(255) not null,
    job_id varchar(255) not null REFERENCES torrent_bulk_jobs(id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    previous_versions varchar(255) not null check ((previous_versions in ('KEEP','REMOVE_TORRENT','REMOVE_TORRENT_AND_FILES'))),
    snapshot TEXT not null,
    primary key (job_id)
);

CREATE TABLE user_permission_overrides (
    user_id TEXT NOT NULL REFERENCES users(id),
    permission TEXT NOT NULL,
    effect TEXT NOT NULL CHECK(effect IN ('ALLOW','DENY')),
    PRIMARY KEY(user_id,permission)
);

CREATE TABLE users (
    id TEXT NOT NULL PRIMARY KEY CHECK(length(trim(id)) > 0),
    username TEXT NOT NULL,
    username_normalized TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL,
    email_verified_at TEXT,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('ADMIN','USER')),
    status TEXT NOT NULL CHECK(status IN ('PENDING','ACTIVE','DISABLED','REJECTED')),
    must_change_password INTEGER NOT NULL DEFAULT 0,
    temporary_password_expires_at TEXT,
    security_version INTEGER NOT NULL DEFAULT 0 CHECK(typeof(security_version)='integer' AND security_version >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    password_changed_at TEXT,
    approved_at TEXT,
    approved_by TEXT,
    home_preferences TEXT
);

CREATE INDEX idx_bulk_item_book on torrent_bulk_items (job_id, epl_id, state);

CREATE INDEX idx_bulk_item_hash on torrent_bulk_items (job_id, hash, state);

CREATE INDEX idx_bulk_item_queue on torrent_bulk_items (job_id, state, position);

CREATE INDEX idx_bulk_job_retention on torrent_bulk_jobs (state, updated_at);

CREATE INDEX idx_catalog_author on catalog_books (author);

CREATE INDEX idx_catalog_language on catalog_books (language);

CREATE INDEX idx_catalog_publication_year on catalog_books (publication_year);

CREATE INDEX idx_catalog_status on catalog_books (status);

CREATE INDEX idx_catalog_title on catalog_books (title);

CREATE INDEX idx_cleanup_client_state_hash on torrent_update_cleanup (client_instance_id, state, hash);

CREATE INDEX idx_cleanup_state_checked on torrent_update_cleanup (state, last_checked_at, created_at);

CREATE INDEX idx_download_book on torrent_downloads (epl_id);

CREATE INDEX idx_download_created on torrent_downloads (created_at);

CREATE INDEX idx_download_instance_status on torrent_downloads (client_instance_id, status);

CREATE INDEX idx_events_category_id ON app_events(category, id);

CREATE INDEX idx_events_date ON app_events(created_at);

CREATE INDEX idx_events_operation ON app_events(operation_id);

CREATE UNIQUE INDEX uk_download_identity ON torrent_downloads(client_instance_id, epl_id, hash);

CREATE UNIQUE INDEX uq_cleanup_job_download ON torrent_update_cleanup(job_id, download_id);

CREATE UNIQUE INDEX uq_bulk_item_job_position ON torrent_bulk_items(job_id, position);
