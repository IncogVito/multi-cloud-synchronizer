package com.cloudsync.model.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("storage_devices")
public class StorageDevice {

    @Id
    private String id;

    private String label;
    private String devicePath;
    private String mountPoint;
    private String filesystemUuid;
    private Long sizeBytes;
    private Instant firstSeenAt;
    private Instant lastSeenAt;

    public StorageDevice() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getDevicePath() { return devicePath; }
    public void setDevicePath(String devicePath) { this.devicePath = devicePath; }

    public String getMountPoint() { return mountPoint; }
    public void setMountPoint(String mountPoint) { this.mountPoint = mountPoint; }

    public String getFilesystemUuid() { return filesystemUuid; }
    public void setFilesystemUuid(String filesystemUuid) { this.filesystemUuid = filesystemUuid; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
