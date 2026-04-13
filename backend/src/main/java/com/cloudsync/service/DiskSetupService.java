package com.cloudsync.service;

import com.cloudsync.client.HostAgentClient;
import com.cloudsync.exception.HostAgentException;
import com.cloudsync.model.entity.StorageDevice;
import com.cloudsync.repository.StorageDeviceRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class DiskSetupService {

    private static final Logger LOG = LoggerFactory.getLogger(DiskSetupService.class);

    private final HostAgentClient hostAgent;
    private final StorageDeviceRepository storageDeviceRepository;
    private final jakarta.inject.Provider<AppContextService> appContextServiceProvider;
    /** Path on the HOST where the drive is mounted — used by host agent. */
    private final String hostMountPath;
    /** Path inside THIS container where the bind-mount lands. */
    private final String containerMountPath;

    public DiskSetupService(HostAgentClient hostAgent,
                            StorageDeviceRepository storageDeviceRepository,
                            jakarta.inject.Provider<AppContextService> appContextServiceProvider,
                            @Value("${app.external-drive-path-host}") String hostMountPath,
                            @Value("${app.external-drive-path}") String containerMountPath) {
        this.hostAgent = hostAgent;
        this.storageDeviceRepository = storageDeviceRepository;
        this.appContextServiceProvider = appContextServiceProvider;
        this.hostMountPath = hostMountPath;
        this.containerMountPath = containerMountPath;
    }

    public String getHostMountPath() {
        return hostMountPath;
    }

    public String getContainerMountPath() {
        return containerMountPath;
    }

    @Serdeable
    public record DriveStatus(boolean mounted, String drivePath, String drivePathHost, Long freeBytes, String deviceId, String label) {}

    @Serdeable
    public record DiskInfo(String name, String path, String size, String type, String mountpoint, String label, String vendor, String model) {}

    public DriveStatus getDriveStatus() {
        try {
            Optional<StorageDevice> device = findMountedDevice();
            if (device.isEmpty()) {
                return new DriveStatus(false, null, null, null, null, null);
            }
            StorageDevice d = device.get();
            Long freeBytes = queryFreeBytes();
            return new DriveStatus(true, containerMountPath, hostMountPath, freeBytes, d.getId(), d.getLabel());
        } catch (Exception e) {
            LOG.warn("getDriveStatus failed, reporting unmounted: {}", e.getMessage());
            return new DriveStatus(false, null, null, null, null, null);
        }
    }

    private Long queryFreeBytes() {
        try {
            var status = hostAgent.checkDrive(hostMountPath);
            return status.freeBytes();
        } catch (HostAgentException e) {
            LOG.warn("queryFreeBytes via host agent failed for {}: {}", hostMountPath, e.getMessage());
            return null;
        }
    }

    public List<DiskInfo> listDisks() {
        try {
            return hostAgent.listDisks().stream()
                    .map(d -> new DiskInfo(d.name(), d.path(), d.size(), d.type(),
                            d.mountpoint(), d.label(), d.vendor(), d.model()))
                    .toList();
        } catch (HostAgentException e) {
            LOG.warn("listDisks failed via host agent: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the device currently mounted at {@code mountPath} by reading /proc/mounts,
     * or {@code null} if nothing is mounted there.
     */
    private static String deviceAtMountPoint(String mountPath) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(java.nio.file.Path.of("/proc/mounts"))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && parts[1].equals(mountPath)) {
                    return parts[0];
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not read /proc/mounts: {}", e.getMessage());
        }
        return null;
    }

    private boolean isDeviceMountedAt(String device, String mountPath) {
        String current = deviceAtMountPoint(mountPath);
        if (current == null) return false;
        // Strip bind-mount notation like /dev/sdc[/some/path]
        int bracket = current.indexOf('[');
        if (bracket >= 0) current = current.substring(0, bracket).trim();
        return current.equals(device);
    }

    public DriveStatus mountAndRegister(String device) {
        if (isDeviceMountedAt(device, containerMountPath)) {
            LOG.info("Device {} already mounted at {}, skipping mount step", device, containerMountPath);
        } else {
            try {
                LOG.debug("Mounting {} device with agent", device);
                hostAgent.mountDrive(device, hostMountPath);
            } catch (HostAgentException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("already mounted")) {
                    LOG.info("Device {} already mounted (reported by host agent), continuing registration", device);
                } else {
                    LOG.warn("Mount failed for {} via host agent: {}", device, e.getMessage());
                    throw new IllegalStateException(
                            "device=" + device + ", host_mount_path=" + hostMountPath + ", reason: " + e.getMessage());
                }
            }
        }

        String uuid;
        String label;
        try {
            var idResult = hostAgent.readDeviceId(device);
            uuid = idResult.uuid();
            label = idResult.label();
        } catch (HostAgentException e) {
            LOG.warn("Could not read UUID for device {} via host agent: {}", device, e.getMessage());
            uuid = null;
            label = null;
        }

        if (uuid == null) {
            LOG.warn("Could not read UUID for device {}, generating random", device);
            uuid = UUID.randomUUID().toString();
        }

        Long sizeBytes = querySizeBytes();

        String finalUuid = uuid;
        Optional<StorageDevice> existing = storageDeviceRepository.findByFilesystemUuid(uuid);
        boolean isNew = existing.isEmpty();
        StorageDevice storageDevice = existing.orElseGet(() -> {
            StorageDevice d = new StorageDevice();
            d.setId(UUID.randomUUID().toString());
            d.setFilesystemUuid(finalUuid);
            d.setFirstSeenAt(Instant.now());
            return d;
        });

        storageDevice.setDevicePath(device);
        storageDevice.setMountPoint(containerMountPath);
        storageDevice.setLabel(label);
        storageDevice.setSizeBytes(sizeBytes);
        storageDevice.setLastSeenAt(Instant.now());
        if (isNew) {
            storageDeviceRepository.save(storageDevice);
        } else {
            storageDeviceRepository.update(storageDevice);
        }

        Long freeBytes = queryFreeBytes();
        return new DriveStatus(true, containerMountPath, hostMountPath, freeBytes, storageDevice.getId(), label);
    }

    public void unmount() {
        Optional<StorageDevice> mounted = findMountedDevice();
        try {
            hostAgent.unmountDrive(hostMountPath);
        } catch (HostAgentException e) {
            throw new IllegalStateException("Odmontowanie nie powiodło się: " + e.getMessage());
        }
        if (mounted.isPresent()) {
            AppContextService ctxService = appContextServiceProvider.get();
            if (ctxService.isContextDevice(mounted.get().getId())) {
                ctxService.clear();
            }
        }
    }

    public Optional<StorageDevice> findMountedDevice() {
        String device = deviceAtMountPoint(containerMountPath);
        if (device == null || device.isBlank()) {
            return Optional.empty();
        }
        // Strip bind-mount notation like /dev/sdc[/mnt/external-drive]
        int bracketIdx = device.indexOf('[');
        if (bracketIdx >= 0) {
            device = device.substring(0, bracketIdx).trim();
        }
        String uuid = null;
        try {
            uuid = hostAgent.readDeviceId(device).uuid();
        } catch (HostAgentException e) {
            LOG.warn("Could not read UUID for {} via host agent: {}", device, e.getMessage());
        }
        if (uuid == null) return Optional.empty();
        return storageDeviceRepository.findByFilesystemUuid(uuid);
    }

    private Long querySizeBytes() {
        try {
            var status = hostAgent.checkDrive(hostMountPath);
            return status.totalBytes();
        } catch (HostAgentException e) {
            LOG.warn("querySizeBytes via host agent failed for {}: {}", hostMountPath, e.getMessage());
            return null;
        }
    }
}
