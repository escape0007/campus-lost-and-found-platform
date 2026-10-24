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
