package com.cloudsync.service;

import com.cloudsync.client.HostAgentClient;
import com.cloudsync.exception.HostAgentException;
import com.cloudsync.model.entity.StorageDevice;
import com.cloudsync.repository.StorageDeviceRepository;
import com.cloudsync.util.ShellExecutor;
import io.micronaut.context.annotation.Value;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class DiskSetupService {

    private static final Logger LOG = LoggerFactory.getLogger(DiskSetupService.class);

    private final ShellExecutor shell;
    private final HostAgentClient hostAgent;
    private final StorageDeviceRepository storageDeviceRepository;
    private final jakarta.inject.Provider<AppContextService> appContextServiceProvider;
    /** Path on the HOST where the drive is mounted — used by host agent. */
    private final String hostMountPath;
    /** Path inside THIS container where the bind-mount lands — used for df, findmnt, etc. */
    private final String containerMountPath;

    public DiskSetupService(ShellExecutor shell,
                            HostAgentClient hostAgent,
                            StorageDeviceRepository storageDeviceRepository,
                            jakarta.inject.Provider<AppContextService> appContextServiceProvider,
                            @Value("${app.external-drive-path-host}") String hostMountPath,
                            @Value("${app.external-drive-path}") String containerMountPath) {
        this.shell = shell;
        this.hostAgent = hostAgent;
        this.storageDeviceRepository = storageDeviceRepository;
        this.appContextServiceProvider = appContextServiceProvider;
        this.hostMountPath = hostMountPath;
        this.containerMountPath = containerMountPath;
    }

    public String getHostMountPath() {
        return hostMountPath;
    }

    @Serdeable
    public record DriveStatus(boolean mounted, String drivePath, Long freeBytes, String deviceId, String label) {}

    @Serdeable
    public record DiskInfo(String name, String path, String size, String type, String mountpoint, String label, String vendor, String model) {}

    public DriveStatus getDriveStatus() {
        // Only report mounted=true when a StorageDevice row exists for whatever is
        // mounted at mountPoint — i.e. the user explicitly went through mountAndRegister.
        // A directory existing (or even an ad-hoc mount done outside the app) is not enough.
        try {
            Optional<StorageDevice> device = findMountedDevice();
            if (device.isEmpty()) {
                return new DriveStatus(false, null, null, null, null);
            }
            StorageDevice d = device.get();
            Long freeBytes = queryFreeBytes(containerMountPath);
            return new DriveStatus(true, containerMountPath, freeBytes, d.getId(), d.getLabel());
        } catch (Exception e) {
            LOG.warn("getDriveStatus failed, reporting unmounted: {}", e.getMessage());
            return new DriveStatus(false, null, null, null, null);
        }
    }

    private Long queryFreeBytes(String path) {
        ShellExecutor.ShellResult result = shell.execute("bash", "-c",
                "df -B1 --output=avail " + path + " 2>/dev/null | tail -1 | tr -d ' '");
        try {
            if (result.isSuccess() && !result.stdout().isBlank()) {
                return Long.parseLong(result.stdout().trim());
            }
        } catch (NumberFormatException ignored) {}
        return null;
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

    private boolean isDeviceMountedAt(String device, String mountPath) {
        ShellExecutor.ShellResult r = shell.execute("bash", "-c",
                "findmnt -n -o SOURCE " + mountPath + " 2>/dev/null");
        if (!r.isSuccess() || r.stdout().isBlank()) return false;
        String current = r.stdout().trim();
        int bracket = current.indexOf('[');
        if (bracket >= 0) current = current.substring(0, bracket).trim();
        return current.equals(device);
    }

    public DriveStatus mountAndRegister(String device) {
        if (isDeviceMountedAt(device, containerMountPath)) {
            LOG.info("Device {} already mounted at {}, skipping mount step", device, containerMountPath);
        } else {
            try {
                hostAgent.mountDrive(device, hostMountPath);
            } catch (HostAgentException e) {
                LOG.warn("Mount failed for {} via host agent: {}", device, e.getMessage());
                throw new IllegalStateException(
                        "device=" + device + ", host_mount_path=" + hostMountPath + ", reason: " + e.getMessage());
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

        Long sizeBytes = querySizeBytes(containerMountPath);

        String finalUuid = uuid;
        StorageDevice storageDevice = storageDeviceRepository.findByFilesystemUuid(uuid).orElseGet(() -> {
            StorageDevice d = new StorageDevice();
            d.setId(UUID.randomUUID().toString());
            d.setFilesystemUuid(finalUuid);
            d.setFirstSeenAt(Instant.now());
            return d;
        });

        storageDevice.setDevicePath(device);
        storageDevice.setMountPoint(hostMountPath);
        storageDevice.setLabel(label);
        storageDevice.setSizeBytes(sizeBytes);
        storageDevice.setLastSeenAt(Instant.now());
        storageDeviceRepository.save(storageDevice);

        Long freeBytes = queryFreeBytes(containerMountPath);
        return new DriveStatus(true, containerMountPath, freeBytes, storageDevice.getId(), label);
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
        ShellExecutor.ShellResult result = shell.execute("bash", "-c",
                "findmnt -n -o SOURCE " + containerMountPath + " 2>/dev/null");
        if (!result.isSuccess() || result.stdout().isBlank()) {
            return Optional.empty();
        }
        String device = result.stdout().trim();
        // findmnt may return bind-mount notation like /dev/sdc[/mnt/external-drive] — strip the bracket part
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

    private Long querySizeBytes(String path) {
        ShellExecutor.ShellResult result = shell.execute("bash", "-c",
                "df -B1 --output=size " + path + " 2>/dev/null | tail -1 | tr -d ' '");
        try {
            if (result.isSuccess() && !result.stdout().isBlank()) {
                return Long.parseLong(result.stdout().trim());
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }
}
