ALTER TABLE vs_task ADD COLUMN parent_id VARCHAR(36);
CREATE INDEX idx_task_parent ON vs_task(parent_id,status);
ALTER TABLE vs_segment ADD COLUMN embedding MEDIUMTEXT;
ALTER TABLE vs_video ADD COLUMN index_execution_id VARCHAR(36);
CREATE TABLE vs_transcript (
 id VARCHAR(36) PRIMARY KEY, video_id VARCHAR(36) NOT NULL, version_id VARCHAR(36) NOT NULL,
 start_ms BIGINT NOT NULL,end_ms BIGINT NOT NULL,content TEXT NOT NULL,
 UNIQUE(video_id,version_id,start_ms,end_ms)
);
