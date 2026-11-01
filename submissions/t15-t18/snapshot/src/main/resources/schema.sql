CREATE TABLE IF NOT EXISTS commit_order (id INT PRIMARY KEY, counter BIGINT NOT NULL);
INSERT INTO commit_order (id,counter) SELECT 1,0 WHERE NOT EXISTS (SELECT 1 FROM commit_order WHERE id=1);
CREATE TABLE IF NOT EXISTS notices (
 id VARCHAR(36) PRIMARY KEY, author_id VARCHAR(80) NOT NULL, author_name VARCHAR(80) NOT NULL,
 type VARCHAR(8) NOT NULL CHECK(type IN ('LOST','FOUND')),
 title VARCHAR(400) NOT NULL, category VARCHAR(40) NOT NULL, occurrence_date DATE NOT NULL,
 approximate BOOLEAN NOT NULL, area VARCHAR(40) NOT NULL, description VARCHAR(5000) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('OPEN','CLAIM_UNDER_REVIEW','AWAITING_COLLECTION','RETURNED','CLOSED','HIDDEN')),
 hidden BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL, material_version BIGINT NOT NULL,
 created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL, closed_reason VARCHAR(2000)
);
CREATE INDEX IF NOT EXISTS idx_notice_author ON notices(author_id);
CREATE TABLE IF NOT EXISTS notice_photos (
 id VARCHAR(36) PRIMARY KEY, notice_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 content_type VARCHAR(30) NOT NULL, data BYTEA NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS notice_revisions (
 notice_id VARCHAR(36) NOT NULL REFERENCES notices(id), version BIGINT NOT NULL,
 action VARCHAR(20) NOT NULL, actor VARCHAR(80) NOT NULL, occurred_at VARCHAR(40) NOT NULL,
 reason VARCHAR(2000) NOT NULL, changed_fields VARCHAR(1000) NOT NULL, status VARCHAR(30) NOT NULL,
 snapshot_json TEXT NOT NULL, PRIMARY KEY(notice_id,version)
);
CREATE TABLE IF NOT EXISTS notice_events (
 id VARCHAR(36) PRIMARY KEY, notice_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 kind VARCHAR(30) NOT NULL, version BIGINT NOT NULL, material_version BIGINT NOT NULL,
 trigger_order BIGINT NOT NULL UNIQUE, created_at VARCHAR(40) NOT NULL,
 UNIQUE(notice_id,version)
);
CREATE TABLE IF NOT EXISTS notice_audit (
 id VARCHAR(36) PRIMARY KEY, notice_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 actor_id VARCHAR(80) NOT NULL, action VARCHAR(20) NOT NULL, previous_state VARCHAR(30),
 new_state VARCHAR(30) NOT NULL, occurred_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS idempotency_records (
 actor_id VARCHAR(80) NOT NULL, operation VARCHAR(100) NOT NULL, request_key VARCHAR(128) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, notice_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 PRIMARY KEY(actor_id,operation,request_key)
);
-- Integration seam for the future claim/workflow module. No public fixture/state-write endpoints.
CREATE TABLE IF NOT EXISTS claim_guards (
 id VARCHAR(36) PRIMARY KEY, found_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 lost_id VARCHAR(36) REFERENCES notices(id), status VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_guard_found ON claim_guards(found_id,status);
CREATE INDEX IF NOT EXISTS idx_guard_lost ON claim_guards(lost_id,status);
CREATE TABLE IF NOT EXISTS active_reservations (
 notice_id VARCHAR(36) PRIMARY KEY REFERENCES notices(id), claim_id VARCHAR(36) NOT NULL
);
-- Accounts are platform identities only. campus_verified cannot be set, and registration always stores STUDENT.
CREATE TABLE IF NOT EXISTS accounts (
 id VARCHAR(36) PRIMARY KEY,
 username VARCHAR(32) NOT NULL,
 username_key VARCHAR(32) NOT NULL,
 display_name VARCHAR(80) NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(20) NOT NULL DEFAULT 'STUDENT' CHECK (role IN ('STUDENT','MODERATOR')),
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
 campus_verified BOOLEAN NOT NULL DEFAULT FALSE CHECK (campus_verified = FALSE),
 failed_attempts INT NOT NULL DEFAULT 0,
 window_started_at VARCHAR(40),
 locked_until VARCHAR(40),
 created_at VARCHAR(40) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_accounts_username_key ON accounts(username_key);
CREATE TABLE IF NOT EXISTS account_audit (
 id VARCHAR(36) PRIMARY KEY,
 actor_id VARCHAR(80) NOT NULL,
 action VARCHAR(40) NOT NULL,
 target VARCHAR(80) NOT NULL,
 outcome VARCHAR(20) NOT NULL,
 occurred_at VARCHAR(40) NOT NULL
);
-- T15-T19: claim workflow, private case conversation, and moderation
CREATE TABLE IF NOT EXISTS claims (
 id VARCHAR(36) PRIMARY KEY,
 found_notice_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 finder_id VARCHAR(80) NOT NULL,
 claimant_id VARCHAR(80) NOT NULL,
 lost_notice_id VARCHAR(36) REFERENCES notices(id),
 evidence VARCHAR(1000) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','HELD','APPROVED','REJECTED','WITHDRAWN','ITEM_RETURNED','RETURNED','DISPUTED')),
 decision_reason VARCHAR(2000),
 reviewer_id VARCHAR(80),
 finder_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
 claimant_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
 disputed BOOLEAN NOT NULL DEFAULT FALSE,
 dispute_reason VARCHAR(2000),
 created_at VARCHAR(40) NOT NULL,
 decided_at VARCHAR(40),
 completed_at VARCHAR(40)
);
CREATE INDEX IF NOT EXISTS idx_claim_found ON claims(found_notice_id,status);
CREATE INDEX IF NOT EXISTS idx_claim_claimant ON claims(claimant_id,status);
CREATE INDEX IF NOT EXISTS idx_claim_lost ON claims(lost_notice_id,status);
CREATE TABLE IF NOT EXISTS case_messages (
 id VARCHAR(36) PRIMARY KEY,
 claim_id VARCHAR(36) NOT NULL REFERENCES claims(id),
 sender_id VARCHAR(80) NOT NULL,
 content VARCHAR(1000) NOT NULL,
 created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_case_msg_claim ON case_messages(claim_id,created_at);
CREATE TABLE IF NOT EXISTS reports (
 id VARCHAR(36) PRIMARY KEY,
 target_type VARCHAR(20) NOT NULL CHECK(target_type IN ('NOTICE','CLAIM','CLAIM_DISPUTE')),
 target_id VARCHAR(36) NOT NULL,
 reporter_id VARCHAR(80) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED','DISMISSED')),
 resolution VARCHAR(30),
 moderator_id VARCHAR(80),
 resolution_note VARCHAR(2000),
 created_at VARCHAR(40) NOT NULL,
 resolved_at VARCHAR(40)
);
CREATE INDEX IF NOT EXISTS idx_report_status ON reports(status,created_at);
