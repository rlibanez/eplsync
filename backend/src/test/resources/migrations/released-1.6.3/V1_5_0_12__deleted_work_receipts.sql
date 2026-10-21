-- Minimal replay protection survives deletion of a cleanup job and its detailed history.
CREATE TABLE deleted_work_receipts (
 source varchar(16) NOT NULL CHECK(source IN ('downloads','calibre')),
 kind varchar(16) NOT NULL CHECK(kind IN ('cleanup','retry')),
 request_id varchar(64) NOT NULL,
 deleted_at bigint NOT NULL,
 PRIMARY KEY(source,kind,request_id)
);
