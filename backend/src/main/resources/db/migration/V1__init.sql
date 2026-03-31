CREATE TABLE IF NOT EXISTS icloud_accounts (
    id TEXT PRIMARY KEY,
    apple_id TEXT NOT NULL UNIQUE,
    display_name TEXT,
    session_id TEXT,
    last_sync_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS photos (
    id TEXT PRIMARY KEY,
    icloud_photo_id TEXT,
    account_id TEXT REFERENCES icloud_accounts(id),
    filename TEXT NOT NULL,
    file_path TEXT NOT NULL,
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
    media_type TEXT DEFAULT 'PHOTO'
);

CREATE TABLE IF NOT EXISTS virtual_folders (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id TEXT REFERENCES virtual_folders(id),
    folder_type TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS photo_folder_assignments (
    photo_id TEXT REFERENCES photos(id),
    folder_id TEXT REFERENCES virtual_folders(id),
    PRIMARY KEY (photo_id, folder_id)
);

CREATE TABLE IF NOT EXISTS device_status (
    id TEXT PRIMARY KEY,
    device_type TEXT NOT NULL,
    is_connected INTEGER DEFAULT 0,
    last_checked_at TIMESTAMP,
    details TEXT
);

INSERT OR IGNORE INTO device_status (id, device_type, is_connected) VALUES
    ('device-external-drive', 'EXTERNAL_DRIVE', 0),
    ('device-iphone', 'IPHONE', 0),
    ('device-icloud', 'ICLOUD', 0);
