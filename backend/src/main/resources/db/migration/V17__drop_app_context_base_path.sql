-- D3: drop app_context.base_path. The global base path is no longer used for file
-- operations — each account's folder is account.sync_folder_path. app_context keeps
-- only device context (mount point lives on storage_devices).
-- SQLite copy-table pattern (Flyway 9; no destructive ALTER ... DROP COLUMN).

CREATE TABLE app_context_new (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    storage_device_id TEXT REFERENCES storage_devices(id),
    set_at TIMESTAMP,
    set_by TEXT
);

INSERT INTO app_context_new (id, storage_device_id, set_at, set_by)
    SELECT id, storage_device_id, set_at, set_by FROM app_context;

DROP TABLE app_context;

ALTER TABLE app_context_new RENAME TO app_context;
