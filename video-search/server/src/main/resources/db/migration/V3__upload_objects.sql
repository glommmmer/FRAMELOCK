CREATE TABLE vs_upload_object (
 object_key VARCHAR(500) PRIMARY KEY, upload_id VARCHAR(36) NOT NULL,
 cleaned BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_upload_object_cleanup ON vs_upload_object(upload_id,cleaned);
