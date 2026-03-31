package com.cloudsync.controller;

import org.junit.jupiter.api.Test;

public class PhotoControllerTest {

    @Test
    void getPhotos_shouldReturn200WithPagedList() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void getPhotos_shouldFilterByAccountIdQueryParam() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void getPhoto_shouldReturn404WhenNotFound() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void getThumbnail_shouldReturn404WhenNoThumbnailExists() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void getThumbnail_shouldReturnImageJpegContentType() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void getFullPhoto_shouldReturn404WhenNotSyncedToDisk() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void postSync_shouldReturn200WithSyncedCount() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteFromICloud_shouldReturn204OnSuccess() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteFromICloud_shouldReturn400WhenPhotoNotSynced() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteFromIPhone_shouldReturn204OnSuccess() {
        // TODO: Implement test – to be completed by secondary model
    }
}
