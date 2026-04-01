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
    private static final String MOUNT_POINT = "/mnt/external-drive";

    private final ShellExecutor shell;
    private final StorageDeviceRepository storageDeviceRepository;
    private final String scriptsDir;

    public DiskSetupService(ShellExecutor shell,
                            StorageDeviceRepository storageDeviceRepository,
                            @Value("${app.scripts-dir:/scripts}") String scriptsDir) {
        this.shell = shell;
        this.storageDeviceRepository = storageDeviceRepository;
        this.scriptsDir = scriptsDir;
    }

    @Serdeable
    public record DriveStatus(boolean mounted, String drivePath, Long freeBytes, String deviceId, String label) {}

    @Serdeable
    public record DiskInfo(String name, String path, String size, String type, String mountpoint, String label, String vendor, String model) {}

    public DriveStatus getDriveStatus() {
        ShellExecutor.ShellResult result = shell.executeScript(scriptsDir, "check-drive.sh");
        if (!result.isSuccess() && result.stdout().isEmpty()) {
            return new DriveStatus(false, null, null, null, null);
        }

        boolean mounted = result.stdout().contains("\"available\": true");
        Long freeBytes = parseJsonLong(result.stdout(), "free_bytes");

        String deviceId = null;
        String label = null;

        if (mounted) {
            Optional<StorageDevice> device = findMountedDevice();
            if (device.isPresent()) {
                deviceId = device.get().getId();
                label = device.get().getLabel();
            }
        }

        return new DriveStatus(mounted, mounted ? MOUNT_POINT : null, freeBytes, deviceId, label);
    }

    public List<DiskInfo> listDisks() {
        ShellExecutor.ShellResult result = shell.executeScript(scriptsDir, "list-disks.sh");
        if (!result.isSuccess() || result.stdout().isBlank()) {
            return List.of();
        }
        return parseDiskList(result.stdout());
    }

    public DriveStatus mountAndRegister(String device) {
        ShellExecutor.ShellResult mountResult = shell.execute("bash", scriptsDir + "/mount-drive.sh", device);
        if (!mountResult.isSuccess()) {
            LOG.warn("Mount failed for {}: {}", device, mountResult.stderr());
            throw new IllegalStateException("Montowanie nie powiodło się: " + mountResult.stderr());
        }

        ShellExecutor.ShellResult blkidResult = shell.execute("bash", scriptsDir + "/read-device-id.sh", device);
        String uuid = parseJsonString(blkidResult.stdout(), "uuid");
        String label = parseJsonString(blkidResult.stdout(), "label");

        if (uuid == null) {
            LOG.warn("Could not read UUID for device {}", device);
            uuid = UUID.randomUUID().toString();
        }

        ShellExecutor.ShellResult sizeResult = shell.execute("bash", "-c",
                "df -B1 --output=size " + MOUNT_POINT + " 2>/dev/null | tail -1 | tr -d ' '");
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
        storageDevice.setMountPoint(MOUNT_POINT);
        storageDevice.setLabel(label);
        storageDevice.setSizeBytes(sizeBytes);
        storageDevice.setLastSeenAt(Instant.now());
        storageDeviceRepository.save(storageDevice);

        ShellExecutor.ShellResult freeResult = shell.execute("bash", "-c",
                "df -B1 --output=avail " + MOUNT_POINT + " 2>/dev/null | tail -1 | tr -d ' '");
        Long freeBytes = null;
        try {
            if (freeResult.isSuccess() && !freeResult.stdout().isBlank()) {
                freeBytes = Long.parseLong(freeResult.stdout().trim());
            }
        } catch (NumberFormatException ignored) {}

        return new DriveStatus(true, MOUNT_POINT, freeBytes, storageDevice.getId(), label);
    }

    public void unmount() {
        ShellExecutor.ShellResult result = shell.executeScript(scriptsDir, "unmount-drive.sh");
        if (!result.isSuccess()) {
            throw new IllegalStateException("Odmontowanie nie powiodło się: " + result.stderr());
        }
    }

    public Optional<StorageDevice> findMountedDevice() {
        ShellExecutor.ShellResult result = shell.execute("bash", "-c",
                "findmnt -n -o SOURCE " + MOUNT_POINT + " 2>/dev/null");
        if (!result.isSuccess() || result.stdout().isBlank()) {
            return Optional.empty();
        }
        String device = result.stdout().trim();
        ShellExecutor.ShellResult blkid = shell.execute("bash", scriptsDir + "/read-device-id.sh", device);
        String uuid = parseJsonString(blkid.stdout(), "uuid");
        if (uuid == null) return Optional.empty();
        return storageDeviceRepository.findByFilesystemUuid(uuid);
    }

    private Long parseJsonLong(String json, String key) {
        try {
            String search = "\"" + key + "\": ";
            int idx = json.indexOf(search);
            if (idx < 0) return null;
            int start = idx + search.length();
            int end = json.indexOf('}', start);
            String raw = json.substring(start, end).trim().replace(",", "").trim();
            if (raw.equals("null")) return null;
            return Long.parseLong(raw);
        } catch (Exception e) {
            return null;
        }
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
