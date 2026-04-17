package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class DiskIndexProgressEvent {

    private String phase;
    private int scanned;
    private int total;
    private double percentComplete;
    private String error;
    private int newlyDeleted;

    public DiskIndexProgressEvent() {}

    public DiskIndexProgressEvent(String phase) {
        this.phase = phase;
    }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public int getScanned() { return scanned; }
    public void setScanned(int scanned) { this.scanned = scanned; }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public double getPercentComplete() { return percentComplete; }
    public void setPercentComplete(double percentComplete) { this.percentComplete = percentComplete; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public int getNewlyDeleted() { return newlyDeleted; }
    public void setNewlyDeleted(int newlyDeleted) { this.newlyDeleted = newlyDeleted; }
}
