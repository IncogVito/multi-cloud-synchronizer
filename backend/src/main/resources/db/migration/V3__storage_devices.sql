CREATE TABLE IF NOT EXISTS storage_devices (
    id TEXT PRIMARY KEY,
    label TEXT,
    device_path TEXT,
    mount_point TEXT,
    filesystem_uuid TEXT,
    size_bytes BIGINT,
    first_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP
);

ALTER TABLE photos ADD COLUMN storage_device_id TEXT REFERENCES storage_devices(id);
