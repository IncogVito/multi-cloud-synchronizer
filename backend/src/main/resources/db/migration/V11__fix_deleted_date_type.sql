-- Fix deleted_date column type from TEXT to TIMESTAMP so JDBC can parse it correctly.
CREATE TABLE photos_new (
    id TEXT PRIMARY KEY,
    icloud_photo_id TEXT,
    account_id TEXT REFERENCES icloud_accounts(id),
    filename TEXT,
    file_path TEXT,
    thumbnail_path TEXT,
    file_size BIGINT,
    width INT,
    height INT,
    created_date TIMESTAMP,
    imported_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    checksum TEXT,
    synced_to_disk INTEGER DEFAULT 0,
    exists_on_icloud INTEGER DEFAULT 1,
    exists_on_iphone INTEGER,
    media_type TEXT DEFAULT 'PHOTO',
    storage_device_id TEXT REFERENCES storage_devices(id),
    sync_status TEXT,
    asset_token TEXT,
    source_provider TEXT DEFAULT 'ICLOUD',
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_date TIMESTAMP
);

INSERT INTO photos_new SELECT
    id, icloud_photo_id, account_id, filename, file_path,
    thumbnail_path, file_size, width, height, created_date,
    imported_date, checksum, synced_to_disk, exists_on_icloud,
    exists_on_iphone, media_type, storage_device_id, sync_status, asset_token,
    source_provider, deleted, deleted_date
FROM photos;

DROP TABLE photos;
ALTER TABLE photos_new RENAME TO photos;
