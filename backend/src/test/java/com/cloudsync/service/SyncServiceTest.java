package com.cloudsync.service;

import org.junit.jupiter.api.Test;

public class SyncServiceTest {

    @Test
    void syncFromICloud_shouldDownloadNewPhotosAndSaveToDb() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void syncFromICloud_shouldSkipPhotosAlreadySyncedToDisk() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void syncFromICloud_shouldThrowDriveNotAvailableWhenMountPointMissing() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void syncFromICloud_shouldThrowWhenAccountHasNoActiveSession() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void syncFromICloud_shouldUpdateLastSyncAtAfterCompletion() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void syncFromICloud_shouldContinueSyncingOnIndividualPhotoFailure() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteFromICloud_shouldCallICloudServiceAndMarkPhotoAsNotOnICloud() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteFromICloud_shouldThrowPhotoNotSyncedWhenNotOnDisk() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteFromIPhone_shouldMarkPhotoAsNotOnIPhone() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteFromIPhone_shouldThrowPhotoNotSyncedWhenNotOnDisk() {
        // TODO: Implement test – to be completed by secondary model
    }
}
