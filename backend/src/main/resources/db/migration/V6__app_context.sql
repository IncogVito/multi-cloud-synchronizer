CREATE TABLE IF NOT EXISTS app_context (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    storage_device_id TEXT REFERENCES storage_devices(id),
    base_path TEXT,
    set_at TIMESTAMP,
    set_by TEXT
);

INSERT OR IGNORE INTO app_context (id) VALUES (1);

ALTER TABLE virtual_folders ADD COLUMN storage_device_id TEXT REFERENCES storage_devices(id);
