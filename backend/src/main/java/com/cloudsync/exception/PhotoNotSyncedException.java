package com.cloudsync.exception;

public class PhotoNotSyncedException extends RuntimeException {

    public PhotoNotSyncedException(String photoId) {
        super("Photo " + photoId + " is not synced to disk. Sync it first before deletion.");
    }
}
