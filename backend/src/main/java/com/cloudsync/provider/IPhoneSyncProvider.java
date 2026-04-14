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
import java.util.List;
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
            case SessionState.Scanning s        -> new PrefetchStatus("scanning", s.fetched(), null);
            case SessionState.Ready r           -> new PrefetchStatus("ready", r.photos().size(), r.photos().size());
            case SessionState.Failed ignored    -> new PrefetchStatus("error", 0, null);
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
        try {
            Files.delete(photoPath);
            LOG.info("Deleted iPhone photo: {}", photoPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete iPhone photo: " + photoId, e);
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * iPhone is expected to be already mounted by the device-check flow (status tile).
     * This method only verifies the mount is active and scans DCIM.
     */
    private void doScan(String sessionId) {
        try {
            Path dcimPath = Path.of(iphoneContainerPath, DCIM_SUBDIR);
            if (!Files.isDirectory(dcimPath)) {
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

            LOG.info("Scanning DCIM [session={}]…", sessionId);
            List<PhotoAsset> photos = scanDcim(sessionId);
            LOG.info("DCIM scan complete [session={}]: {} photos", sessionId, photos.size());
            sessions.put(sessionId, SessionState.ready(photos));
        } catch (Exception e) {
            LOG.error("iPhone scan failed [session={}]", sessionId, e);
            sessions.put(sessionId, SessionState.failed(e.getMessage()));
        }
    }

    private static final int SCAN_PROGRESS_INTERVAL = 50;

    private List<PhotoAsset> scanDcim(String sessionId) throws IOException {
        Path dcimPath = Path.of(iphoneContainerPath, DCIM_SUBDIR);
        if (!Files.isDirectory(dcimPath)) {
            LOG.warn("DCIM directory not found at {}", dcimPath);
            return List.of();
        }

        List<PhotoAsset> photos = new ArrayList<>();
        Path mountRoot = Path.of(iphoneContainerPath);
        AtomicInteger counter = new AtomicInteger(0);

        try (Stream<Path> stream = Files.walk(dcimPath)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> isPhotoExtension(p.getFileName().toString()))
                  .forEach(p -> {
                      try {
                          BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                          String relPath = mountRoot.relativize(p).toString();
                          photos.add(new PhotoAsset(
                                  relPath,
                                  p.getFileName().toString(),
                                  attrs.size(),
                                  attrs.creationTime().toInstant(),
                                  null,
                                  null,
                                  relPath
                          ));
                          int count = counter.incrementAndGet();
                          if (count % SCAN_PROGRESS_INTERVAL == 0) {
                              sessions.put(sessionId, SessionState.scanning(count));
                          }
                      } catch (IOException e) {
                          LOG.warn("Failed to read attributes for {}: {}", p, e.getMessage());
                      }
                  });
        }
        return photos;
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

    // ── session state ─────────────────────────────────────────────────────────

    private sealed interface SessionState
            permits SessionState.Scanning, SessionState.Ready, SessionState.Failed {

        record Scanning(int fetched) implements SessionState {}
        record Ready(List<PhotoAsset> photos) implements SessionState {}
        record Failed(String error) implements SessionState {}

        static SessionState scanning(int fetched) { return new Scanning(fetched); }
        static SessionState ready(List<PhotoAsset> photos) { return new Ready(photos); }
        static SessionState failed(String error) { return new Failed(error); }
    }
}
