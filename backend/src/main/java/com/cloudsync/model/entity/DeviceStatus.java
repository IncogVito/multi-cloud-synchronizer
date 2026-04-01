package com.cloudsync.model.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity("device_status")
public class DeviceStatus {

    @Id
    private String id;

    @MappedProperty("device_type")
    private String deviceType;

    @MappedProperty("is_connected")
    private boolean connected;

    @MappedProperty("last_checked_at")
    private Instant lastCheckedAt;

    private String details;
    private String status;

    public DeviceStatus() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }

    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
