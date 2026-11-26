CREATE TABLE vs_user (
 username VARCHAR(80) PRIMARY KEY, password_hash VARCHAR(100) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE vs_library (
 id VARCHAR(36) PRIMARY KEY, owner VARCHAR(80) NOT NULL, name VARCHAR(120) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_library_owner ON vs_library(owner,created_at,id);
CREATE TABLE vs_video (
 id VARCHAR(36) PRIMARY KEY, library_id VARCHAR(36) NOT NULL, owner VARCHAR(80) NOT NULL,
 name VARCHAR(250) NOT NULL, object_key VARCHAR(500) NOT NULL, file_hash VARCHAR(64) NOT NULL,
 file_size BIGINT NOT NULL, duration_ms BIGINT NOT NULL, has_audio BOOLEAN NOT NULL,
 status VARCHAR(24) NOT NULL, active_version VARCHAR(36), deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_video_list ON vs_video(owner,library_id,deleted,created_at,id);
CREATE TABLE vs_upload (
 id VARCHAR(36) PRIMARY KEY, video_id VARCHAR(36) NOT NULL UNIQUE, owner VARCHAR(80) NOT NULL,
 library_id VARCHAR(36) NOT NULL, name VARCHAR(250) NOT NULL, file_size BIGINT NOT NULL,
 file_hash VARCHAR(64) NOT NULL, total_parts INT NOT NULL, status VARCHAR(24) NOT NULL,
 execution_id VARCHAR(36), lease_until TIMESTAMP NULL, expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_upload_library ON vs_upload(library_id,status,expires_at);
CREATE TABLE vs_upload_part (
 upload_id VARCHAR(36) NOT NULL, part_no INT NOT NULL, size_bytes BIGINT NOT NULL,
 file_hash VARCHAR(64) NOT NULL, object_key VARCHAR(500) NOT NULL,
 PRIMARY KEY(upload_id,part_no)
);
CREATE TABLE vs_task (
 id VARCHAR(36) PRIMARY KEY, owner VARCHAR(80) NOT NULL, video_id VARCHAR(36),
 version_id VARCHAR(36), kind VARCHAR(24) NOT NULL, stage VARCHAR(24) NOT NULL,
 status VARCHAR(24) NOT NULL, attempts INT NOT NULL DEFAULT 0, execution_id VARCHAR(36),
 lease_until TIMESTAMP NULL, next_run TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE, input_json MEDIUMTEXT,
 output_json MEDIUMTEXT, error_message VARCHAR(1000), idempotency_key VARCHAR(200) NOT NULL UNIQUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_task_pending ON vs_task(status,next_run,id);
CREATE TABLE vs_outbox (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, sent_at TIMESTAMP NULL
);
CREATE TABLE vs_segment (
 id VARCHAR(36) PRIMARY KEY, video_id VARCHAR(36) NOT NULL, version_id VARCHAR(36) NOT NULL,
 owner VARCHAR(80) NOT NULL, library_id VARCHAR(36) NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL,
 transcript MEDIUMTEXT, ocr_text MEDIUMTEXT, description MEDIUMTEXT NOT NULL, evidence_json MEDIUMTEXT,
 UNIQUE(video_id,version_id,start_ms,end_ms)
);
CREATE INDEX idx_segment_scope ON vs_segment(owner,library_id,version_id);
CREATE TABLE vs_favorite (
 id VARCHAR(36) PRIMARY KEY, owner VARCHAR(80) NOT NULL, video_id VARCHAR(36) NOT NULL,
 start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(owner,video_id,start_ms,end_ms)
);
