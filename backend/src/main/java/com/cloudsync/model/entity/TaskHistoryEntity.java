package com.cloudsync.model.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("task_history")
public class TaskHistoryEntity {

    @Id
    private String id;
    private String type;
    private String accountId;
    private String provider;
    private String status;
    private Instant createdAt;
    private Instant completedAt;
    private int totalItems;
    private int succeededItems;
    private int failedItems;
    private String errorMessage;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public int getTotalItems() { return totalItems; }
    public void setTotalItems(int totalItems) { this.totalItems = totalItems; }

    public int getSucceededItems() { return succeededItems; }
    public void setSucceededItems(int succeededItems) { this.succeededItems = succeededItems; }

    public int getFailedItems() { return failedItems; }
    public void setFailedItems(int failedItems) { this.failedItems = failedItems; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
