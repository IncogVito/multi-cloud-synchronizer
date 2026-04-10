-- SQLite does not support dropping NOT NULL via ALTER COLUMN,
-- so we recreate the photos table with file_path and filename nullable.
PRAGMA foreign_keys = OFF;

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
    asset_token TEXT
);

INSERT INTO photos_new SELECT
    id, icloud_photo_id, account_id, filename, file_path,
    thumbnail_path, file_size, width, height, created_date,
    imported_date, checksum, synced_to_disk, exists_on_icloud,
    exists_on_iphone, media_type, storage_device_id, sync_status, asset_token
FROM photos;

DROP TABLE photos;
ALTER TABLE photos_new RENAME TO photos;

PRAGMA foreign_keys = ON;
