CREATE TABLE IF NOT EXISTS task_history (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    account_id TEXT,
    provider TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    total_items INTEGER NOT NULL DEFAULT 0,
    succeeded_items INTEGER NOT NULL DEFAULT 0,
    failed_items INTEGER NOT NULL DEFAULT 0,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS task_sync_phases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL REFERENCES task_history(id),
    phase TEXT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS task_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL REFERENCES task_history(id),
    item_status TEXT NOT NULL,
    photo_id TEXT,
    photo_name TEXT,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_task_history_created_at ON task_history(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_history_type ON task_history(type);
CREATE INDEX IF NOT EXISTS idx_task_history_status ON task_history(status);
CREATE INDEX IF NOT EXISTS idx_task_sync_phases_task_id ON task_sync_phases(task_id);
CREATE INDEX IF NOT EXISTS idx_task_items_task_id ON task_items(task_id);
