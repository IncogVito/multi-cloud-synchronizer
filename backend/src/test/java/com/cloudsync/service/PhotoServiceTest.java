package com.cloudsync.service;

import com.cloudsync.model.dto.MonthSummaryResponse;
import com.cloudsync.model.dto.PhotoListResponse;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoServiceTest {

    @Mock PhotoRepository photoRepository;
    @Mock AppContextService appContextService;

    PhotoService service;

    @BeforeEach
    void setUp() {
        service = new PhotoService(photoRepository, appContextService);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Page<Photo> mockPage(List<Photo> photos) {
        return Page.of(photos, Pageable.from(0, Math.max(1, photos.size())), (long) photos.size());
    }

    private Photo photo(String id) {
        return photo(id, null);
    }

    private Photo photo(String id, String accountId) {
        Photo p = new Photo();
        p.setId(id);
        p.setAccountId(accountId);
        p.setFilename("img_" + id + ".jpg");
        p.setSyncedToDisk(true);
        p.setStorageDeviceId("dev-shared");
        p.setCreatedDate(Instant.parse("2024-06-01T10:00:00Z"));
        return p;
    }

    // ── listPhotos ─────────────────────────────────────────────────────────────

    @Test
    void listPhotos_shouldReturnPagedResultsWhenNoAccountFilter() {
        List<Photo> photos = List.of(photo("1"), photo("2"));
        when(photoRepository.findAll(any(Pageable.class))).thenReturn(mockPage(photos));

        PhotoListResponse resp = service.listPhotos(null, null, null, null, 0, 20);

        assertThat(resp.photos()).hasSize(2);
        assertThat(resp.total()).isEqualTo(2);
        verify(photoRepository).findAll(any(Pageable.class));
    }

    @Test
    void listPhotos_shouldFilterByAccountId() {
        List<Photo> photos = List.of(photo("1", "acc-1"));
        when(photoRepository.findByAccountId(eq("acc-1"), any(Pageable.class))).thenReturn(mockPage(photos));

        PhotoListResponse resp = service.listPhotos("acc-1", null, null, null, 0, 20);

        assertThat(resp.photos()).hasSize(1);
        verify(photoRepository).findByAccountId(eq("acc-1"), any(Pageable.class));
    }

    @Test
    void listPhotos_shouldFilterBySyncedToDisk() {
        List<Photo> photos = List.of(photo("1"), photo("2"), photo("3"));
        when(photoRepository.findBySyncedToDisk(eq(true), any(Pageable.class))).thenReturn(mockPage(photos));

        PhotoListResponse resp = service.listPhotos(null, true, null, null, 0, 20);

        assertThat(resp.total()).isEqualTo(3);
        verify(photoRepository).findBySyncedToDisk(eq(true), any(Pageable.class));
    }

    @Test
    void listPhotos_shouldFilterByAccountIdAndSyncedToDisk() {
        List<Photo> photos = List.of(photo("1", "acc-1"));
        when(photoRepository.findByAccountIdAndSyncedToDisk("acc-1", true)).thenReturn(photos);

        PhotoListResponse resp = service.listPhotos("acc-1", true, null, null, 0, 20);

        assertThat(resp.photos()).hasSize(1);
        assertThat(resp.total()).isEqualTo(1);
        verify(photoRepository).findByAccountIdAndSyncedToDisk("acc-1", true);
    }

    @Test
    void listPhotos_withYearMonthAndAccount_filtersByDateRange() {
        List<Photo> photos = List.of(photo("1", "acc-1"));
        when(photoRepository.findBySyncedToDiskAndAccountIdAndCreatedDateBetween(
                eq(true), eq("acc-1"), any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(mockPage(photos));

        PhotoListResponse resp = service.listPhotos("acc-1", null, "2024-06", null, 0, 20);

        assertThat(resp.photos()).hasSize(1);
        verify(photoRepository).findBySyncedToDiskAndAccountIdAndCreatedDateBetween(
                eq(true), eq("acc-1"),
                eq(Instant.parse("2024-06-01T00:00:00Z")),
                eq(Instant.parse("2024-07-01T00:00:00Z")),
                any(Pageable.class));
    }

    @Test
    void listPhotos_withDateRangeAndAccount_passesUnsortedPageableToCustomQuery() {
        when(photoRepository.findBySyncedToDiskAndAccountIdAndCreatedDateBetween(
                eq(true), eq("acc-1"), any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(mockPage(List.of()));

        service.listPhotos("acc-1", true, "2024-06", null, 0, 2000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(photoRepository).findBySyncedToDiskAndAccountIdAndCreatedDateBetween(
                eq(true), eq("acc-1"), any(Instant.class), any(Instant.class), captor.capture());
        assertThat(captor.getValue().getSort().isSorted()).isFalse();
    }

    @Test
    void listPhotos_pageAndSizeForwardedCorrectly() {
        when(photoRepository.findAll(any(Pageable.class))).thenReturn(mockPage(List.of()));

        PhotoListResponse resp = service.listPhotos(null, null, null, null, 3, 50);

        assertThat(resp.page()).isEqualTo(3);
        assertThat(resp.size()).isEqualTo(50);
    }

    /**
     * Acceptance criterion: listPhotos(accountA) never returns photos belonging to accountB,
     * even when both accounts live on the same storage device.
     */
    @Test
    void listPhotos_neverReturnsPhotosOfAnotherAccountOnSameDevice() {
        Photo a1 = photo("a1", "acc-A");
        Photo a2 = photo("a2", "acc-A");
        Photo b1 = photo("b1", "acc-B"); // same device, different account

        // Repository behaves like a real per-account store backed by account_id.
        when(photoRepository.findByAccountId(eq("acc-A"), any(Pageable.class)))
                .thenAnswer(inv -> mockPage(List.of(a1, a2)));

        PhotoListResponse resp = service.listPhotos("acc-A", null, null, null, 0, 20);

        assertThat(resp.photos()).extracting(PhotoResponseId -> PhotoResponseId.id())
                .containsExactlyInAnyOrder("a1", "a2")
                .doesNotContain("b1");
        // Device-scoped lookups must never be used to serve photos.
        verify(photoRepository, never()).findBySyncedToDiskAndStorageDeviceId(anyBoolean(), anyString(), any(Pageable.class));
    }

    // ── getMonthsSummary ────────────────────────────────────────────────────────

    @Test
    void getMonthsSummary_groupsAccountPhotosByMonth() {
        Photo a = photo("a1", "acc-A");
        a.setCreatedDate(Instant.parse("2024-06-10T10:00:00Z"));
        a.setFileSize(100L);
        Photo b = photo("a2", "acc-A");
        b.setCreatedDate(Instant.parse("2024-05-10T10:00:00Z"));
        b.setFileSize(50L);
        when(photoRepository.findByAccountIdAndSyncedToDisk("acc-A", true)).thenReturn(List.of(a, b));

        List<MonthSummaryResponse> result = service.getMonthsSummary("acc-A");

        assertThat(result).extracting(MonthSummaryResponse::yearMonth)
                .containsExactly("2024-06", "2024-05"); // newest first
        verify(photoRepository).findByAccountIdAndSyncedToDisk("acc-A", true);
    }

    @Test
    void getMonthsSummary_usesAccountScopedQueryNotDevice() {
        when(photoRepository.findByAccountIdAndSyncedToDisk("acc-A", true)).thenReturn(List.of());

        service.getMonthsSummary("acc-A");

        verify(photoRepository).findByAccountIdAndSyncedToDisk("acc-A", true);
        verify(photoRepository, never()).findByStorageDeviceIdAndSyncedToDisk(anyString(), anyBoolean());
    }

    // ── getPhoto ───────────────────────────────────────────────────────────────

    @Test
    void getPhoto_shouldReturnEmptyWhenNotFound() {
        when(photoRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.getPhoto("missing")).isEmpty();
    }

    @Test
    void getPhoto_shouldReturnResponseWhenFound() {
        when(photoRepository.findById("p1")).thenReturn(Optional.of(photo("p1")));

        assertThat(service.getPhoto("p1")).isPresent()
                .hasValueSatisfying(r -> assertThat(r.id()).isEqualTo("p1"));
    }

    // ── getThumbnailBytes ──────────────────────────────────────────────────────

    @Test
    void getThumbnailBytes_shouldReturnEmptyWhenPhotoHasNoThumbnailPath() throws IOException {
        Photo p = photo("p1");
        p.setThumbnailPath(null);
        when(photoRepository.findById("p1")).thenReturn(Optional.of(p));

        assertThat(service.getThumbnailBytes("p1")).isEmpty();
    }

    @Test
    void getThumbnailBytes_shouldReturnEmptyWhenThumbnailFileDoesNotExist() throws IOException {
        Photo p = photo("p1");
        p.setThumbnailPath("/nonexistent/path/thumb.jpg");
        when(photoRepository.findById("p1")).thenReturn(Optional.of(p));

        assertThat(service.getThumbnailBytes("p1")).isEmpty();
    }

    @Test
    void getThumbnailBytes_shouldReturnBytesWhenFileExists(@TempDir Path tempDir) throws IOException {
        Path thumb = tempDir.resolve("thumb.jpg");
        Files.write(thumb, new byte[]{1, 2, 3});

        Photo p = photo("p1");
        p.setThumbnailPath(thumb.toString());
        when(photoRepository.findById("p1")).thenReturn(Optional.of(p));

        assertThat(service.getThumbnailBytes("p1")).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3));
    }

    // ── getFullPhotoBytes ──────────────────────────────────────────────────────

    @Test
    void getFullPhotoBytes_shouldReturnEmptyWhenNotSyncedToDisk() throws IOException {
        Photo p = photo("p1");
        p.setSyncedToDisk(false);
        when(photoRepository.findById("p1")).thenReturn(Optional.of(p));

        assertThat(service.getFullPhotoBytes("p1")).isEmpty();
    }

    @Test
    void getFullPhotoBytes_shouldReturnEmptyWhenFileDoesNotExist() throws IOException {
        Photo p = photo("p1");
        p.setSyncedToDisk(true);
        p.setFilePath("/nonexistent/file.jpg");
        when(photoRepository.findById("p1")).thenReturn(Optional.of(p));

        assertThat(service.getFullPhotoBytes("p1")).isEmpty();
    }

    @Test
    void getFullPhotoBytes_shouldReturnBytesForJpeg(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("photo.jpg");
        byte[] content = {(byte) 0xFF, (byte) 0xD8, 42};
        Files.write(file, content);

        Photo p = photo("p1");
        p.setSyncedToDisk(true);
        p.setFilePath(file.toString());
        when(photoRepository.findById("p1")).thenReturn(Optional.of(p));

        assertThat(service.getFullPhotoBytes("p1")).hasValueSatisfying(b -> assertThat(b).containsExactly(content));
    }
}
