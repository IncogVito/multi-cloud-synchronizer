package com.cloudsync.service;

import com.cloudsync.model.dto.DeviceStatusResponse;
import com.cloudsync.model.entity.DeviceStatus;
import com.cloudsync.model.enums.DeviceType;
import com.cloudsync.repository.DeviceStatusRepository;
import com.cloudsync.util.ShellExecutor;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;

@Singleton
public class DeviceStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceStatusService.class);

    private final DeviceStatusRepository deviceStatusRepository;
    private final ShellExecutor shellExecutor;

    @Value("${EXTERNAL_DRIVE_PATH:/mnt/external-drive}")
    private String externalDrivePath;

    @Value("${app.scripts-dir:/scripts}")
    private String scriptsDir;

    public DeviceStatusService(DeviceStatusRepository deviceStatusRepository, ShellExecutor shellExecutor) {
        this.deviceStatusRepository = deviceStatusRepository;
        this.shellExecutor = shellExecutor;
    }

    public List<DeviceStatusResponse> getAllStatuses() {
        return StreamSupport.stream(deviceStatusRepository.findAll().spliterator(), false)
                .map(this::toResponse)
                .toList();
    }

    public DeviceStatusResponse checkDrive() {
        boolean connected = Files.isDirectory(Path.of(externalDrivePath));
        return updateStatus(DeviceType.EXTERNAL_DRIVE.name(), connected, null);
    }

    public DeviceStatusResponse checkIPhone() {
        ShellExecutor.ShellResult result = shellExecutor.executeScript(scriptsDir, "detect-iphone.sh");
        boolean connected = result.isSuccess() && result.stdout().contains("connected");
        return updateStatus(DeviceType.IPHONE.name(), connected, result.stdout());
    }

    private DeviceStatusResponse updateStatus(String deviceType, boolean connected, String details) {
        DeviceStatus status = deviceStatusRepository.findByDeviceType(deviceType)
                .orElseGet(() -> {
                    DeviceStatus s = new DeviceStatus();
                    s.setId("device-" + deviceType.toLowerCase().replace("_", "-"));
                    s.setDeviceType(deviceType);
                    return s;
                });

        status.setConnected(connected);
        status.setLastCheckedAt(Instant.now());
        status.setDetails(details);

        if (deviceStatusRepository.existsById(status.getId())) {
            deviceStatusRepository.update(status);
        } else {
            deviceStatusRepository.save(status);
        }

        return toResponse(status);
    }

    private DeviceStatusResponse toResponse(DeviceStatus status) {
        return new DeviceStatusResponse(
                status.getId(),
                status.getDeviceType(),
                status.isConnected(),
                status.getLastCheckedAt(),
                status.getDetails()
        );
    }
}
