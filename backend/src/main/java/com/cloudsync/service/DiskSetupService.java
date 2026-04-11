package com.cloudsync.service;

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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class DiskSetupService {

    private static final Logger LOG = LoggerFactory.getLogger(DiskSetupService.class);

    private final ShellExecutor shell;
    private final StorageDeviceRepository storageDeviceRepository;
    private final jakarta.inject.Provider<AppContextService> appContextServiceProvider;
    private final String scriptsDir;
    /** Path on the HOST where the drive is mounted — passed to mount-drive.sh via the bridge. */
    private final String hostMountPath;
    /** Path inside THIS container where the bind-mount lands — used for df, findmnt, etc. */
    private final String containerMountPath;

    public DiskSetupService(ShellExecutor shell,
                            StorageDeviceRepository storageDeviceRepository,
                            jakarta.inject.Provider<AppContextService> appContextServiceProvider,
                            @Value("${app.scripts-dir:/scripts}") String scriptsDir,
                            @Value("${app.external-drive-path-host}") String hostMountPath,
                            @Value("${app.external-drive-path}") String containerMountPath) {
        this.shell = shell;
        this.storageDeviceRepository = storageDeviceRepository;
        this.appContextServiceProvider = appContextServiceProvider;
        this.scriptsDir = scriptsDir;
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
        ShellExecutor.ShellResult result = shell.executeScript(scriptsDir, "list-disks.sh");
        if (!result.isSuccess() || result.stdout().isBlank()) {
            return List.of();
        }
        return parseDiskList(result.stdout());
    }

    public DriveStatus mountAndRegister(String device) {
        // Pass the HOST-side mount path explicitly so the script never falls back to its hardcoded default.
        ShellExecutor.ShellResult mountResult = shell.execute("bash", scriptsDir + "/mount-drive.sh", device, hostMountPath);
        if (!mountResult.isSuccess()) {
            int exit = mountResult.exitCode();
            String stderr = mountResult.stderr() == null ? "" : mountResult.stderr().trim();
            if (exit == 124) {
                LOG.warn("Mount bridge timeout for {} (exit=124): {}", device, stderr);
                throw new IllegalStateException(
                        "Bridge timeout — pipe-daemon nie odpowiada na hoście" +
                        " (device=" + device + ", host_mount_path=" + hostMountPath + ")");
            }
            String scriptMsg = parseJsonString(mountResult.stdout(), "message");
            LOG.warn("Mount failed for {} (exit={}): script='{}' stderr='{}'", device, exit, scriptMsg, stderr);
            StringBuilder msg = new StringBuilder("device=").append(device)
                    .append(", host_mount_path=").append(hostMountPath)
                    .append(", exit=").append(exit);
            if (scriptMsg != null && !scriptMsg.isBlank()) msg.append(", reason: ").append(scriptMsg);
            if (!stderr.isBlank()) msg.append(", stderr: ").append(stderr);
            throw new IllegalStateException(msg.toString());
        }

        ShellExecutor.ShellResult blkidResult = shell.execute("bash", scriptsDir + "/read-device-id.sh", device);
        String uuid = parseJsonString(blkidResult.stdout(), "uuid");
        String label = parseJsonString(blkidResult.stdout(), "label");

        if (uuid == null) {
            LOG.warn("Could not read UUID for device {}", device);
            uuid = UUID.randomUUID().toString();
        }

        ShellExecutor.ShellResult sizeResult = shell.execute("bash", "-c",
                "df -B1 --output=size " + containerMountPath + " 2>/dev/null | tail -1 | tr -d ' '");
        Long sizeBytes = null;
        try {
            if (sizeResult.isSuccess() && !sizeResult.stdout().isBlank()) {
                sizeBytes = Long.parseLong(sizeResult.stdout().trim());
            }
        } catch (NumberFormatException ignored) {}

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

        ShellExecutor.ShellResult freeResult = shell.execute("bash", "-c",
                "df -B1 --output=avail " + containerMountPath + " 2>/dev/null | tail -1 | tr -d ' '");
        Long freeBytes = null;
        try {
            if (freeResult.isSuccess() && !freeResult.stdout().isBlank()) {
                freeBytes = Long.parseLong(freeResult.stdout().trim());
            }
        } catch (NumberFormatException ignored) {}

        return new DriveStatus(true, containerMountPath, freeBytes, storageDevice.getId(), label);
    }

    public void unmount() {
        Optional<StorageDevice> mounted = findMountedDevice();
        ShellExecutor.ShellResult result = shell.executeScript(scriptsDir, "unmount-drive.sh");
        if (!result.isSuccess()) {
            throw new IllegalStateException("Odmontowanie nie powiodło się: " + result.stderr());
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
        ShellExecutor.ShellResult blkid = shell.execute("bash", scriptsDir + "/read-device-id.sh", device);
        String uuid = parseJsonString(blkid.stdout(), "uuid");
        if (uuid == null) return Optional.empty();
        return storageDeviceRepository.findByFilesystemUuid(uuid);
    }

    private String parseJsonString(String json, String key) {
        try {
            String search = "\"" + key + "\": ";
            int idx = json.indexOf(search);
            if (idx < 0) return null;
            int start = idx + search.length();
            if (json.charAt(start) != '"') return null;
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<DiskInfo> parseDiskList(String json) {
        try {
            // Simple JSON array parsing using basic string manipulation
            // Each object: {"name":"sdb","path":"/dev/sdb","size":"2T",...}
            var result = new java.util.ArrayList<DiskInfo>();
            int i = 0;
            while ((i = json.indexOf('{', i)) >= 0) {
                int end = json.indexOf('}', i);
                if (end < 0) break;
                String obj = json.substring(i, end + 1);
                result.add(new DiskInfo(
                        extractStr(obj, "name"),
                        extractStr(obj, "path"),
                        extractStr(obj, "size"),
                        extractStr(obj, "type"),
                        extractStr(obj, "mountpoint"),
                        extractStr(obj, "label"),
                        extractStr(obj, "vendor"),
                        extractStr(obj, "model")
                ));
                i = end + 1;
            }
            return result;
        } catch (Exception e) {
            LOG.warn("Failed to parse disk list JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractStr(String json, String key) {
        String search = "\"" + key + "\": ";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        if (start >= json.length()) return null;
        char first = json.charAt(start);
        if (first == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        } else if (first == 'n') {
            return null; // null
        }
        return null;
    }
}
