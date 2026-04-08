ALTER TABLE icloud_accounts ADD COLUMN sync_folder_path TEXT;
ALTER TABLE icloud_accounts ADD COLUMN storage_device_id TEXT REFERENCES storage_devices(id);
ALTER TABLE icloud_accounts ADD COLUMN organize_by TEXT DEFAULT 'MONTH';
