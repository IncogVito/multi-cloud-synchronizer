package com.cloudsync.model.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("app_context")
public class AppContextEntity {

    @Id
    private Integer id;

    private String storageDeviceId;
    private String basePath;
    private Instant setAt;
    private String setBy;

    public AppContextEntity() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getStorageDeviceId() { return storageDeviceId; }
    public void setStorageDeviceId(String storageDeviceId) { this.storageDeviceId = storageDeviceId; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public Instant getSetAt() { return setAt; }
    public void setSetAt(Instant setAt) { this.setAt = setAt; }

    public String getSetBy() { return setBy; }
    public void setSetBy(String setBy) { this.setBy = setBy; }
}
