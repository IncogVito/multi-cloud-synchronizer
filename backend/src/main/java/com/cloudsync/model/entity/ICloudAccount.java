package com.cloudsync.model.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity("icloud_accounts")
public class ICloudAccount {

    @Id
    private String id;

    private String appleId;
    private String displayName;
    private String sessionId;
    private Instant lastSyncAt;
    private Instant createdAt;
    private String syncFolderPath;
    private String storageDeviceId;
    private String organizeBy;

    public ICloudAccount() {}

    public ICloudAccount(String id, String appleId, String displayName, String sessionId,
                          Instant lastSyncAt, Instant createdAt) {
        this.id = id;
        this.appleId = appleId;
        this.displayName = displayName;
        this.sessionId = sessionId;
        this.lastSyncAt = lastSyncAt;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAppleId() { return appleId; }
    public void setAppleId(String appleId) { this.appleId = appleId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getSyncFolderPath() { return syncFolderPath; }
    public void setSyncFolderPath(String syncFolderPath) { this.syncFolderPath = syncFolderPath; }

    public String getStorageDeviceId() { return storageDeviceId; }
    public void setStorageDeviceId(String storageDeviceId) { this.storageDeviceId = storageDeviceId; }

    public String getOrganizeBy() { return organizeBy; }
    public void setOrganizeBy(String organizeBy) { this.organizeBy = organizeBy; }
}
