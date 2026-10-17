CREATE TABLE telegram_settings (
 user_id varchar(36) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 enabled boolean NOT NULL DEFAULT false,
 token TEXT NOT NULL,
 chat_id varchar(32) NOT NULL,
 success boolean NOT NULL DEFAULT false,
 warning boolean NOT NULL DEFAULT true,
 error boolean NOT NULL DEFAULT true,
 language varchar(2) NOT NULL DEFAULT 'es'
);
CREATE TABLE telegram_deliveries (
 id varchar(36) PRIMARY KEY,
 user_id varchar(36) NOT NULL REFERENCES telegram_settings(user_id) ON DELETE CASCADE,
 operation_id varchar(128) NOT NULL,
 action varchar(64) NOT NULL,
 outcome varchar(24) NOT NULL,
 permission varchar(64) NOT NULL,
 configuration varchar(64) NOT NULL,
 message TEXT NOT NULL CHECK(length(message)<=4096),
 state varchar(16) NOT NULL CHECK(state IN ('PENDING','SENDING','SENT','FAILED')),
 attempts integer NOT NULL DEFAULT 0,
 created_at bigint NOT NULL,
 next_attempt bigint NOT NULL,
 UNIQUE(user_id,operation_id,action,outcome)
);
CREATE INDEX idx_telegram_pending ON telegram_deliveries(state,next_attempt);
