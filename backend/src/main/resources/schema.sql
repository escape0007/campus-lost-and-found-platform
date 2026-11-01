CREATE TABLE IF NOT EXISTS commit_order (id INT PRIMARY KEY, counter BIGINT NOT NULL);
INSERT INTO commit_order (id,counter) SELECT 1,0 WHERE NOT EXISTS (SELECT 1 FROM commit_order WHERE id=1);
CREATE TABLE IF NOT EXISTS notices (
 id VARCHAR(36) PRIMARY KEY, author_id VARCHAR(80) NOT NULL, author_name VARCHAR(80) NOT NULL,
 type VARCHAR(8) NOT NULL CHECK(type IN ('LOST','FOUND')),
 title VARCHAR(400) NOT NULL, category VARCHAR(40) NOT NULL, occurrence_date DATE NOT NULL,
 approximate BOOLEAN NOT NULL, area VARCHAR(40) NOT NULL, description VARCHAR(5000) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('OPEN','CLAIM_UNDER_REVIEW','AWAITING_COLLECTION','RETURNED','CLOSED')),
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
-- T12 suggestions. A row is a ranked comparison of public fields, never ownership proof.
-- config_version t12-2026-10-25 is the frozen category / word / area / date rule.
CREATE TABLE IF NOT EXISTS match_candidates (
 lost_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 found_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 lost_material_version BIGINT NOT NULL,
 found_material_version BIGINT NOT NULL,
 category_score INT NOT NULL,
 text_score INT NOT NULL,
 area_score INT NOT NULL,
 date_score INT NOT NULL,
 confidence INT NOT NULL,
 reasons_json TEXT NOT NULL,
 config_version VARCHAR(40) NOT NULL,
 evaluated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY (lost_id, found_id, lost_material_version, found_material_version)
);
-- Dismissal stays for this user even if either notice is edited or returns to Open.
CREATE TABLE IF NOT EXISTS ignored_pairs (
 user_id VARCHAR(80) NOT NULL,
 canonical_pair_id VARCHAR(80) NOT NULL,
 dismissed_at VARCHAR(40) NOT NULL,
 PRIMARY KEY (user_id, canonical_pair_id)
);
-- T13 in-site alerts. New-match alerts is the proposed default and is still awaiting confirmation.
-- Dedup is recipient + lost + found + both material versions. Photo or timestamp changes do not use a new key.
CREATE TABLE IF NOT EXISTS match_preferences (
 user_id VARCHAR(80) PRIMARY KEY,
 mode VARCHAR(30) NOT NULL CHECK (mode IN ('NEW_MATCH_ALERTS','MANUAL_CHECKING')),
 latest_enabled_at VARCHAR(40) NOT NULL,
 latest_enable_order BIGINT NOT NULL,
 version BIGINT NOT NULL,
 proposed_default BOOLEAN NOT NULL
);
CREATE TABLE IF NOT EXISTS match_alerts (
 id VARCHAR(36) PRIMARY KEY,
 recipient_id VARCHAR(80) NOT NULL,
 lost_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 found_id VARCHAR(36) NOT NULL REFERENCES notices(id),
 lost_material_version BIGINT NOT NULL,
 found_material_version BIGINT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 confidence INT NOT NULL,
 trigger_order BIGINT NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 read_at VARCHAR(40),
 config_version VARCHAR(40) NOT NULL,
 UNIQUE (recipient_id, lost_id, found_id, lost_material_version, found_material_version)
);
CREATE TABLE IF NOT EXISTS alert_event_deliveries (
 event_id VARCHAR(36) PRIMARY KEY REFERENCES notice_events(id),
 state VARCHAR(20) NOT NULL,
 attempted_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS account_audit (
 id VARCHAR(36) PRIMARY KEY,
 actor_id VARCHAR(80) NOT NULL,
 action VARCHAR(40) NOT NULL,
 target VARCHAR(80) NOT NULL,
 outcome VARCHAR(20) NOT NULL,
 occurred_at VARCHAR(40) NOT NULL
);
