package com.cloudsync.provider;

import com.cloudsync.client.HostAgentClient;
import com.cloudsync.exception.HostAgentException;
import com.cloudsync.model.dto.PhotoAsset;
import com.cloudsync.model.dto.PrefetchStatus;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Singleton
@Named("IPHONE")
public class IPhoneSyncProvider implements PhotoSyncProvider {

    private static final Logger LOG = LoggerFactory.getLogger(IPhoneSyncProvider.class);

    private static final String DCIM_SUBDIR = "DCIM";
    private static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "heic", "png", "dng", "tiff", "mov", "mp4", "m4v"
    );

    private final HostAgentClient hostAgent;
    private final String iphoneHostMountPath;
    private final String iphoneContainerPath;

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public IPhoneSyncProvider(HostAgentClient hostAgent,
                               @Named("iphoneHostMountPath") String iphoneHostMountPath,
                               @Named("iphoneContainerPath") String iphoneContainerPath

    ) {
        this.hostAgent = hostAgent;
        this.iphoneHostMountPath = iphoneHostMountPath;
        this.iphoneContainerPath = iphoneContainerPath;
    }

    @Override
    public String providerType() {
        return "IPHONE";
    }

    /**
     * Mounts the iPhone via script and scans the DCIM directory asynchronously.
     * Returns immediately; poll {@link #getPrefetchStatus} until "ready" or "error".
     */
    @Override
    public void prefetch(String sessionId) {
        sessions.put(sessionId, SessionState.scanning(0));
        Thread.ofVirtual()
              .name("iphone-prefetch-" + sessionId)
              .start(() -> doScan(sessionId));
    }

    @Override
    public PrefetchStatus getPrefetchStatus(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return null;
        return switch (state) {
            case SessionState.Mounting ignored  -> new PrefetchStatus("mounting", 0, null, null);
            case SessionState.Scanning s        -> new PrefetchStatus("scanning", s.fetched(), null, null);
            case SessionState.Ready r           -> new PrefetchStatus("ready", r.photos().size(), r.photos().size(), null);
            case SessionState.Failed f          -> new PrefetchStatus("error", 0, null, f.error());
        };
    }

    @Override
    public List<PhotoAsset> listAllPhotos(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (!(state instanceof SessionState.Ready ready)) {
            throw new IllegalStateException("iPhone scan not ready for session: " + sessionId);
        }
        return ready.photos();
    }

    @Override
    public byte[] downloadPhoto(String photoId, String sessionId) throws IOException {
        Path photoPath = resolveAndValidatePath(photoId);
        return Files.readAllBytes(photoPath);
    }

    @Override
    public void deletePhoto(String photoId, String sessionId) {
        Path photoPath = resolveAndValidatePath(photoId);
        boolean exists = Files.exists(photoPath);
        boolean writable = exists && Files.isWritable(photoPath);
        try {
            Files.delete(photoPath);
            LOG.info("Deleted iPhone photo: {}", photoPath);
        } catch (IOException e) {
            String detail = String.format(
                    "Failed to delete iPhone photo %s at %s [exists=%s, writable=%s]: %s - %s",
                    photoId, photoPath, exists, writable,
                    e.getClass().getSimpleName(), e.getMessage());
            LOG.warn(detail, e);
            throw new RuntimeException(detail, e);
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void doScan(String sessionId) {
        try {
            Path dcimPath = Path.of(iphoneContainerPath, DCIM_SUBDIR);
            if (!Files.isDirectory(dcimPath)) {
                sessions.put(sessionId, SessionState.mounting());
                try {
                    var mountResult = hostAgent.iphoneMount(iphoneHostMountPath);
                    if (!mountResult.mounted()) {
                        LOG.error("iPhone not mounted and re-mount failed [session={}]: {}", sessionId, mountResult.error());
                        sessions.put(sessionId, SessionState.failed("iPhone not mounted: " + mountResult.error()));
                        return;
                    }
                    LOG.info("Re-mounted iPhone [session={}]", sessionId);
                } catch (HostAgentException e) {
                    LOG.error("iPhone re-mount via host agent failed [session={}]: {}", sessionId, e.getMessage());
                    sessions.put(sessionId, SessionState.failed("iPhone not mounted: " + e.getMessage()));
                    return;
                }
            }

            sessions.put(sessionId, SessionState.scanning(0));
            LOG.info("Scanning iPhone mount [session={}]…", sessionId);
            List<PhotoAsset> photos = scanMount(sessionId);
            LOG.info("Mount scan complete [session={}]: {} photos", sessionId, photos.size());
            sessions.put(sessionId, SessionState.ready(photos));
        } catch (Exception e) {
            LOG.error("iPhone scan failed [session={}]", sessionId, e);
            sessions.put(sessionId, SessionState.failed(e.getMessage()));
        }
    }

    private static final int SCAN_PROGRESS_INTERVAL = 50;
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mov", "mp4", "m4v");

    /**
     * Only media directories on the AFC mount.
     * PhotoData/ contains thumbnails and edits for every photo — scanning it would
     * produce thousands of duplicates with the same filenames as originals.
     */
    private static final Set<String> SCAN_SUBDIRS = Set.of("DCIM", "Recordings");

    private List<PhotoAsset> scanMount(String sessionId) throws IOException {
        Path mountRoot = Path.of(iphoneContainerPath);
        if (!Files.isDirectory(mountRoot)) {
            LOG.warn("iPhone mount root not found at {}", mountRoot);
            return List.of();
        }

        AtomicInteger walkCounter = new AtomicInteger(0);
        List<Path> allFiles = new ArrayList<>();
        for (String subdir : SCAN_SUBDIRS) {
            Path dir = mountRoot.resolve(subdir);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> !isHidden(p))
                      .filter(p -> isPhotoExtension(p.getFileName().toString()))
                      .peek(p -> {
                          int cnt = walkCounter.incrementAndGet();
                          if (cnt % SCAN_PROGRESS_INTERVAL == 0) {
                              sessions.put(sessionId, SessionState.scanning(cnt));
                          }
                      })
                      .forEach(allFiles::add);
            }
        }

        // Deduplicate by filename+size — keeps first occurrence (DCIM wins over thumbnails/edits).
        Map<String, Path> seen = new LinkedHashMap<>();
        List<String> duplicateLog = new ArrayList<>();
        for (Path p : allFiles) {
            try {
                long size = Files.size(p);
                String key = p.getFileName().toString().toLowerCase() + ":" + size;
                Path existing = seen.putIfAbsent(key, p);
                if (existing != null) {
                    duplicateLog.add(p.getFileName() + " (" + size + " B) — kept: " + mountRoot.relativize(existing) + ", skipped: " + mountRoot.relativize(p));
                }
            } catch (IOException e) {
                seen.putIfAbsent(p.toString(), p);
            }
        }
        allFiles = new ArrayList<>(seen.values());
        if (!duplicateLog.isEmpty()) {
            LOG.info("Deduplication removed {} file(s) [session={}]:", duplicateLog.size(), sessionId);
            duplicateLog.forEach(msg -> LOG.info("  dup: {}", msg));
        }

        // Collect "parentPath/stem" for still images to detect Live Photo pairs
        Set<String> stillStems = new HashSet<>();
        for (Path p : allFiles) {
            String name = p.getFileName().toString();
            if (!VIDEO_EXTENSIONS.contains(fileExtension(name))) {
                stillStems.add(p.getParent().toString() + "/" + fileBaseName(name).toLowerCase());
            }
        }

        List<PhotoAsset> photos = new ArrayList<>();
        AtomicInteger counter = new AtomicInteger(0);

        for (Path p : allFiles) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                String relPath = mountRoot.relativize(p).toString();
                String filename = p.getFileName().toString();

                // Live Photo video component: add _VIDEO suffix so it's distinct from the still
                String ext = fileExtension(filename);
                if (VIDEO_EXTENSIONS.contains(ext)) {
                    String stem = p.getParent().toString() + "/" + fileBaseName(filename).toLowerCase();
                    if (stillStems.contains(stem)) {
                        String origExt = filename.substring(filename.lastIndexOf('.') + 1);
                        filename = fileBaseName(filename) + "_VIDEO." + origExt;
                    }
                }

                photos.add(new PhotoAsset(
                        relPath,
                        filename,
                        attrs.size(),
                        attrs.creationTime().toInstant(),
                        null,
                        null,
                        relPath,
                        null
                ));
                int count = counter.incrementAndGet();
                if (count % SCAN_PROGRESS_INTERVAL == 0) {
                    sessions.put(sessionId, SessionState.scanning(count));
                }
            } catch (IOException e) {
                LOG.warn("Failed to read attributes for {}: {}", p, e.getMessage());
            }
        }
        return photos;
    }


    private static String fileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private static String fileBaseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    /**
     * Resolves a photoId (relative path) against the mount root and validates
     * that the result is still under the mount root (prevents path traversal).
     */
    private Path resolveAndValidatePath(String photoId) {
        Path mountRoot = Path.of(iphoneContainerPath);
        Path resolved = mountRoot.resolve(photoId).normalize();
        if (!resolved.startsWith(mountRoot)) {
            throw new IllegalArgumentException("Photo path escapes mount root: " + photoId);
        }
        return resolved;
    }

    private static boolean isPhotoExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        return PHOTO_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase());
    }

    private static boolean isHidden(Path p) {
        for (Path part : p) {
            if (part.toString().startsWith(".")) return true;
        }
        return false;
    }

    // ── session state ─────────────────────────────────────────────────────────

    private sealed interface SessionState
            permits SessionState.Mounting, SessionState.Scanning, SessionState.Ready, SessionState.Failed {

        record Mounting() implements SessionState {}
        record Scanning(int fetched) implements SessionState {}
        record Ready(List<PhotoAsset> photos) implements SessionState {}
        record Failed(String error) implements SessionState {}

        static SessionState mounting() { return new Mounting(); }
        static SessionState scanning(int fetched) { return new Scanning(fetched); }
        static SessionState ready(List<PhotoAsset> photos) { return new Ready(photos); }
        static SessionState failed(String error) { return new Failed(error); }
    }
}
