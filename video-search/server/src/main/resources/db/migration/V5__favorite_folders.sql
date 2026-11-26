CREATE TABLE vs_favorite_folder (
 id VARCHAR(36) PRIMARY KEY, owner VARCHAR(80) NOT NULL, name VARCHAR(80) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(owner,name)
);
ALTER TABLE vs_favorite ADD COLUMN folder_id VARCHAR(36) NULL;
ALTER TABLE vs_favorite ADD CONSTRAINT fk_favorite_folder FOREIGN KEY (folder_id) REFERENCES vs_favorite_folder(id);
CREATE INDEX idx_favorite_folder ON vs_favorite(owner,folder_id,created_at,id);
