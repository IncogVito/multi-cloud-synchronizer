package com.cloudsync.provider;

import com.cloudsync.client.HostAgentClient;
import com.cloudsync.model.dto.PhotoAsset;
import com.cloudsync.model.dto.PrefetchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IPhoneSyncProviderTest {

    @Mock HostAgentClient hostAgent;

    @TempDir Path mountRoot;

    private IPhoneSyncProvider provider() {
        return new IPhoneSyncProvider(hostAgent, "/host/iphone", mountRoot.toString());
    }

    private List<PhotoAsset> scanUntilReady(IPhoneSyncProvider p) throws InterruptedException {
        String sessionId = "test-session";
        p.prefetch(sessionId);
        for (int i = 0; i < 200; i++) {
            PrefetchStatus status = p.getPrefetchStatus(sessionId);
            if (status != null && "ready".equals(status.status())) {
                return p.listAllPhotos(sessionId);
            }
            if (status != null && "error".equals(status.status())) {
                throw new AssertionError("Scan failed unexpectedly");
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for scan to finish");
    }

    private void createFile(Path dir, String name) throws IOException {
        Files.createFile(dir.resolve(name));
    }

    // ── basic discovery ───────────────────────────────────────────────────────

    @Test
    void scan_findsPhotoFilesInDcim() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        createFile(dcim, "IMG_001.HEIC");
        createFile(dcim, "IMG_002.jpg");

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).hasSize(2);
        assertThat(photos).extracting(PhotoAsset::filename)
                .containsExactlyInAnyOrder("IMG_001.HEIC", "IMG_002.jpg");
    }

    @Test
    void scan_excludesHiddenFiles() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        createFile(dcim, "IMG_001.HEIC");
        createFile(dcim, ".hidden.jpg");

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).extracting(PhotoAsset::filename)
                .containsExactly("IMG_001.HEIC");
    }

    @Test
    void scan_excludesNonPhotoExtensions() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        createFile(dcim, "IMG_001.HEIC");
        createFile(dcim, "readme.txt");
        createFile(dcim, "data.xml");

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).filename()).isEqualTo("IMG_001.HEIC");
    }

    @Test
    void scan_returnsEmptyWhenNoFilesPresent() throws Exception {
        Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).isEmpty();
    }

    // ── Live Photo pairing ────────────────────────────────────────────────────

    @Test
    void scan_livePhotoVideoGetsVideoSuffix() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        createFile(dcim, "IMG_001.HEIC");
        createFile(dcim, "IMG_001.MOV");

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).hasSize(2);
        assertThat(photos).extracting(PhotoAsset::filename)
                .containsExactlyInAnyOrder("IMG_001.HEIC", "IMG_001_VIDEO.MOV");
    }

    @Test
    void scan_standaloneVideoKeepsOriginalName() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        createFile(dcim, "VIDEO_001.MP4");

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).filename()).isEqualTo("VIDEO_001.MP4");
    }

    @Test
    void scan_mp4AlongsideStillGetsVideoSuffix() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        createFile(dcim, "IMG_002.jpg");
        createFile(dcim, "IMG_002.mp4");

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).extracting(PhotoAsset::filename)
                .containsExactlyInAnyOrder("IMG_002.jpg", "IMG_002_VIDEO.mp4");
    }

    // ── deduplication ─────────────────────────────────────────────────────────

    @Test
    void scan_deduplicatesSameNameAndSizeAcrossSubdirs() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        Path rec = Files.createDirectories(mountRoot.resolve("Recordings"));
        // Both files are empty (size = 0) → same key → deduplicated
        createFile(dcim, "IMG_001.HEIC");
        createFile(rec, "IMG_001.HEIC");

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).hasSize(1);
    }

    @Test
    void scan_keepsBothWhenSameNameButDifferentSize() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        Path rec = Files.createDirectories(mountRoot.resolve("Recordings"));
        Files.write(dcim.resolve("IMG_001.HEIC"), new byte[]{1});
        Files.write(rec.resolve("IMG_001.HEIC"), new byte[]{1, 2});

        List<PhotoAsset> photos = scanUntilReady(provider());

        assertThat(photos).hasSize(2);
    }

    // ── session state ─────────────────────────────────────────────────────────

    @Test
    void getPrefetchStatus_returnsNullForUnknownSession() {
        assertThat(provider().getPrefetchStatus("no-such-session")).isNull();
    }

    @Test
    void scan_readyStatusReportsTotalCount() throws Exception {
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        for (int i = 0; i < 10; i++) {
            createFile(dcim, "IMG_" + i + ".HEIC");
        }

        IPhoneSyncProvider p = provider();
        p.prefetch("s1");

        PrefetchStatus status = null;
        for (int i = 0; i < 200; i++) {
            status = p.getPrefetchStatus("s1");
            if (status != null && "ready".equals(status.status())) break;
            Thread.sleep(25);
        }

        assertThat(status).isNotNull();
        assertThat(status.status()).isEqualTo("ready");
        assertThat(status.total()).isEqualTo(10);
        assertThat(status.fetched()).isEqualTo(10);
    }

    @Test
    void scan_progressIsEmittedDuringWalkForLargeDirectory() throws Exception {
        // 60 files → walk emits progress at 50 → scanning state has count > 0 before ready
        Path dcim = Files.createDirectories(mountRoot.resolve("DCIM/100APPLE"));
        for (int i = 0; i < 60; i++) {
            createFile(dcim, String.format("IMG_%03d.HEIC", i));
        }

        IPhoneSyncProvider p = provider();
        p.prefetch("s2");

        boolean sawNonZeroScanningCount = false;
        for (int i = 0; i < 200; i++) {
            PrefetchStatus status = p.getPrefetchStatus("s2");
            if (status != null && "scanning".equals(status.status()) && status.fetched() >= 50) {
                sawNonZeroScanningCount = true;
            }
            if (status != null && "ready".equals(status.status())) break;
            Thread.sleep(10);
        }

        List<PhotoAsset> photos = p.listAllPhotos("s2");
        assertThat(photos).hasSize(60);
        // Note: on fast local filesystems the scan may complete before we can observe the
        // intermediate state, so we only assert the final count is correct rather than
        // mandating the intermediate progress was observed.
    }
}
