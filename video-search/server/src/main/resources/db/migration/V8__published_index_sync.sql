CREATE TABLE vs_index_sync (
 video_id VARCHAR(36) NOT NULL, version_id VARCHAR(36) NOT NULL,
 execution_id VARCHAR(36) NOT NULL, completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(video_id,version_id,execution_id)
);
