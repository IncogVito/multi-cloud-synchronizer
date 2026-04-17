package com.cloudsync.model.dto;

import com.cloudsync.model.enums.SyncPhase;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;

@Serdeable
public class SyncProgressEvent {
    private String accountId;
    private SyncPhase phase;
    private int totalOnCloud;
    private int synced;
    private int failed;
    private int pending;
    private int metadataFetched;
    private double percentComplete;
    private String currentFile;
    private Instant timestamp;
    private Long diskFreeBytes;
    private Long diskPhotoCount;

    public SyncProgressEvent() {}

    public SyncProgressEvent(String accountId, SyncPhase phase) {
        this.accountId = accountId;
        this.phase = phase;
        this.timestamp = Instant.now();
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public SyncPhase getPhase() { return phase; }
    public void setPhase(SyncPhase phase) { this.phase = phase; }
    public int getTotalOnCloud() { return totalOnCloud; }
    public void setTotalOnCloud(int totalOnCloud) { this.totalOnCloud = totalOnCloud; }
    public int getSynced() { return synced; }
    public void setSynced(int synced) { this.synced = synced; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public int getPending() { return pending; }
    public void setPending(int pending) { this.pending = pending; }
    public int getMetadataFetched() { return metadataFetched; }
    public void setMetadataFetched(int metadataFetched) { this.metadataFetched = metadataFetched; }
    public double getPercentComplete() { return percentComplete; }
    public void setPercentComplete(double percentComplete) { this.percentComplete = percentComplete; }
    public String getCurrentFile() { return currentFile; }
    public void setCurrentFile(String currentFile) { this.currentFile = currentFile; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Long getDiskFreeBytes() { return diskFreeBytes; }
    public void setDiskFreeBytes(Long diskFreeBytes) { this.diskFreeBytes = diskFreeBytes; }
    public Long getDiskPhotoCount() { return diskPhotoCount; }
    public void setDiskPhotoCount(Long diskPhotoCount) { this.diskPhotoCount = diskPhotoCount; }
}
