package com.cloudsync.service;

import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ThumbnailServiceTest {

    @Mock PhotoRepository photoRepository;

    ThumbnailService service;

    @BeforeEach
    void setUp() {
        service = new ThumbnailService(photoRepository, null, "/tmp/thumbs");
    }

    private Photo photo(String id) {
        Photo p = new Photo();
        p.setId(id);
        return p;
    }

    // ── account-scoped counting / candidates ─────────────────────────────────

    @Test
    void countMissingByAccount_delegatesToAccountScopedQuery() {
        when(photoRepository.countMissingThumbnailsByAccount("acc-1")).thenReturn(7L);

        assertThat(service.countMissingByAccount("acc-1")).isEqualTo(7L);
        verify(photoRepository).countMissingThumbnailsByAccount("acc-1");
    }

    @Test
    void findCandidates_withAccountId_usesAccountScopedQuery() {
        List<Photo> candidates = List.of(photo("1"), photo("2"));
        when(photoRepository.findSyncedWithoutThumbnailByAccount("acc-1")).thenReturn(candidates);

        assertThat(service.findCandidates("acc-1")).hasSize(2);
        verify(photoRepository).findSyncedWithoutThumbnailByAccount("acc-1");
    }

    @Test
    void findCandidates_withBlankAccountId_fallsBackToAllSyncedWithoutThumbnail() {
        when(photoRepository.findSyncedWithoutThumbnail()).thenReturn(List.of(photo("1")));

        assertThat(service.findCandidates(null)).hasSize(1);
        verify(photoRepository).findSyncedWithoutThumbnail();
    }

    // ── existing generation tests (stubs) ─────────────────────────────────────

    @Test
    void generateThumbnail_shouldCreateJpegFileAt300x300InThumbnailDir() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void generateThumbnail_shouldUpdatePhotoThumbnailPathInDb() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void generateThumbnail_shouldSkipWhenPhotoHasNoFilePath() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void generateThumbnail_shouldSkipWhenSourceFileDoesNotExist() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void generateMissing_shouldSkipPhotosWithExistingThumbnails() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void generateMissing_shouldCallOnGeneratedForEachPhotoEvenOnFailure() {
        // TODO: Implement test – to be completed by secondary model
    }
}
