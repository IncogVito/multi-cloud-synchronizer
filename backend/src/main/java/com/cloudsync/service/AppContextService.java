package com.cloudsync.service;

import com.cloudsync.exception.NoActiveContextException;
import com.cloudsync.model.dto.AppContext;
import com.cloudsync.model.entity.AppContextEntity;
import com.cloudsync.model.entity.StorageDevice;
import com.cloudsync.repository.AppContextRepository;
import com.cloudsync.repository.StorageDeviceRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

@Singleton
public class AppContextService {

    private static final Logger LOG = LoggerFactory.getLogger(AppContextService.class);
    private static final Integer SINGLETON_ID = 1;

    private final AppContextRepository repository;
    private final StorageDeviceRepository storageDeviceRepository;

    private volatile AppContext cached;

    public AppContextService(AppContextRepository repository,
                             StorageDeviceRepository storageDeviceRepository) {
        this.repository = repository;
        this.storageDeviceRepository = storageDeviceRepository;
    }

    public Optional<AppContext> getActive() {
        if (cached != null) return Optional.of(cached);
        try {
            Optional<AppContext> ctx = loadFromDb();
            ctx.ifPresent(c -> cached = c);
            return ctx;
        } catch (io.micronaut.context.exceptions.ConfigurationException e) {
            // Datasource not yet ready (startup race) — expected transiently, no stacktrace needed
            LOG.debug("Failed to load app context (datasource not ready): {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            LOG.warn("Failed to load app context: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    public AppContext requireActive() {
        return getActive().orElseThrow(NoActiveContextException::new);
    }

    public synchronized AppContext setContext(String storageDeviceId, String basePath, boolean createIfMissing) {
        if (storageDeviceId == null || storageDeviceId.isBlank()) {
            throw new IllegalArgumentException("storageDeviceId is required");
        }
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalArgumentException("basePath is required");
        }

        StorageDevice device = storageDeviceRepository.findById(storageDeviceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown storage device: " + storageDeviceId));

        String mountPoint = device.getMountPoint();
        if (mountPoint == null || !isMounted(mountPoint)) {
            throw new IllegalStateException("Dysk nie jest aktualnie zamontowany");
        }

        Path mountPath = Paths.get(mountPoint).toAbsolutePath().normalize();
        Path absolute = Paths.get(basePath).toAbsolutePath().normalize();

        if (!absolute.startsWith(mountPath)) {
            throw new IllegalArgumentException("INVALID_BASE_PATH: ścieżka musi być wewnątrz mount point dysku");
        }

        if (!Files.exists(absolute)) {
            if (createIfMissing) {
                try {
                    Files.createDirectories(absolute);
                } catch (IOException e) {
                    throw new IllegalStateException("Nie udało się utworzyć katalogu: " + e.getMessage(), e);
                }
            } else {
                throw new IllegalArgumentException("Katalog nie istnieje");
            }
        }
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("Ścieżka nie jest katalogiem");
        }
        if (!Files.isWritable(absolute)) {
            throw new IllegalArgumentException("Brak uprawnień do zapisu w katalogu");
        }

        Optional<AppContextEntity> existing = repository.findById(SINGLETON_ID);
        AppContextEntity entity = existing.orElseGet(() -> {
            AppContextEntity e = new AppContextEntity();
            e.setId(SINGLETON_ID);
            return e;
        });
        entity.setStorageDeviceId(storageDeviceId);
        entity.setBasePath(absolute.toString());
        entity.setSetAt(Instant.now());
        if (existing.isPresent()) {
            repository.update(entity);
        } else {
            repository.save(entity);
        }

        AppContext ctx = build(entity, device);
        cached = ctx;
        return ctx;
    }

    public synchronized void clear() {
        try {
            Optional<AppContextEntity> existing = repository.findById(SINGLETON_ID);
            if (existing.isPresent()) {
                AppContextEntity entity = existing.get();
                entity.setStorageDeviceId(null);
                entity.setBasePath(null);
                entity.setSetAt(null);
                repository.update(entity);
            }
        } catch (Exception e) {
            LOG.warn("Failed to clear app context row: {}", e.getMessage(), e);
        } finally {
            cached = null;
        }
    }

    public boolean isContextDevice(String storageDeviceId) {
        return getActive().map(c -> c.storageDeviceId().equals(storageDeviceId)).orElse(false);
    }

    private Optional<AppContext> loadFromDb() {
        return repository.findById(SINGLETON_ID)
                .filter(e -> e.getStorageDeviceId() != null && e.getBasePath() != null)
                .flatMap(e -> storageDeviceRepository.findById(e.getStorageDeviceId())
                        .map(d -> build(e, d)));
    }

    private AppContext build(AppContextEntity entity, StorageDevice device) {
        String mountPoint = device.getMountPoint();
        String basePath = entity.getBasePath();
        String relative = basePath;
        if (mountPoint != null && basePath.startsWith(mountPoint)) {
            relative = basePath.substring(mountPoint.length());
            if (relative.startsWith("/")) relative = relative.substring(1);
        }

        boolean degraded = mountPoint == null || !isMounted(mountPoint);
        Long freeBytes = degraded ? null : queryFreeBytes(basePath);

        return new AppContext(
                device.getId(),
                device.getLabel(),
                mountPoint,
                basePath,
                relative,
                freeBytes,
                entity.getSetAt(),
                degraded
        );
    }

    private boolean isMounted(String mountPoint) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(Path.of("/proc/mounts"))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && parts[1].equals(mountPoint)) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.warn("isMounted check failed for {}: {}", mountPoint, e.getMessage());
        }
        return false;
    }

    private Long queryFreeBytes(String path) {
        try {
            return Files.getFileStore(Path.of(path)).getUsableSpace();
        } catch (IOException e) {
            LOG.warn("queryFreeBytes failed for {}: {}", path, e.getMessage());
            return null;
        }
    }
}
