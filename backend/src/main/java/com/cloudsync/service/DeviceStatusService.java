package com.cloudsync.service;

import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.exception.HostAgentException;
import com.cloudsync.model.dto.AgentStepEvent;
import com.cloudsync.model.dto.DeviceCheckEvent;
import com.cloudsync.model.dto.DeviceStatusResponse;
import com.cloudsync.model.entity.DeviceStatus;
import com.cloudsync.model.enums.DeviceCheckStatus;
import com.cloudsync.model.enums.DeviceType;
import com.cloudsync.repository.DeviceStatusRepository;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


@Singleton
public class DeviceStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceStatusService.class);

    private final DeviceStatusRepository deviceStatusRepository;
    private final HostAgentClient hostAgent;
    private final DiskDetectionAgent diskDetectionAgent;
    private final ICloudServiceClient iCloudServiceClient;

    @Value("${app.external-drive-path}")
    private String externalDrivePath;

    @Value("${app.iphone-mount-path:/mnt/iphone}")
    private String iphoneMountPath;

    public DeviceStatusService(DeviceStatusRepository deviceStatusRepository,
                               HostAgentClient hostAgent,
                               DiskDetectionAgent diskDetectionAgent,
                               ICloudServiceClient iCloudServiceClient) {
        this.deviceStatusRepository = deviceStatusRepository;
        this.hostAgent = hostAgent;
        this.diskDetectionAgent = diskDetectionAgent;
        this.iCloudServiceClient = iCloudServiceClient;
    }

    public List<DeviceStatusResponse> getAllStatuses() {
        return StreamSupport.stream(deviceStatusRepository.findAll().spliterator(), false)
                .map(this::toResponse)
                .toList();
    }

    public Flux<DeviceCheckEvent> checkDriveStream() {
        return Flux.defer(() -> {
            DeviceCheckEvent checkingEvent = new DeviceCheckEvent(
                    DeviceType.EXTERNAL_DRIVE.name(),
                    DeviceCheckStatus.CHECKING.name(),
                    "Sprawdzam dysk zewnętrzny pod " + externalDrivePath + "...",
                    null,
                    false
            );

            boolean available;
            String driveDetails;
            try {
                var driveStatus = hostAgent.checkDrive(externalDrivePath);
                available = driveStatus.available();
                driveDetails = available
                        ? "{\"available\": true, \"path\": \"" + driveStatus.path() + "\", \"free_bytes\": " + driveStatus.freeBytes() + "}"
                        : "{\"available\": false, \"path\": null, \"free_bytes\": null}";
            } catch (HostAgentException e) {
                available = false;
                driveDetails = "{\"available\": false, \"error\": \"" + e.getMessage() + "\"}";
            }

            if (available) {
                DeviceCheckEvent finalEvent = new DeviceCheckEvent(
                        DeviceType.EXTERNAL_DRIVE.name(),
                        DeviceCheckStatus.CONNECTED.name(),
                        "Dysk dostępny pod " + externalDrivePath + ".",
                        driveDetails,
                        true
                );
                persistStatus(DeviceType.EXTERNAL_DRIVE.name(), DeviceCheckStatus.CONNECTED, driveDetails);
                return Flux.just(checkingEvent, finalEvent);
            }

            DeviceCheckEvent agentStartEvent = new DeviceCheckEvent(
                    DeviceType.EXTERNAL_DRIVE.name(),
                    DeviceCheckStatus.CHECKING.name(),
                    "Dysk nie wykryty pod " + externalDrivePath + ". Uruchamiam agenta wykrywania...",
                    driveDetails,
                    false
            );

            Flux<DeviceCheckEvent> agentEvents = diskDetectionAgent.detectDrive()
                    .map(this::mapAgentStep)
                    .doOnNext(evt -> {
                        if (evt.terminal()) {
                            if (DeviceCheckStatus.CONNECTED.name().equals(evt.status())) {
                                String details;
                                try {
                                    var verify = hostAgent.checkDrive(externalDrivePath);
                                    details = verify.available()
                                            ? "{\"available\": true, \"path\": \"" + verify.path() + "\", \"free_bytes\": " + verify.freeBytes() + "}"
                                            : evt.details();
                                } catch (HostAgentException ex) {
                                    details = evt.details();
                                }
                                persistStatus(DeviceType.EXTERNAL_DRIVE.name(), DeviceCheckStatus.CONNECTED, details);
                            } else {
                                persistStatus(DeviceType.EXTERNAL_DRIVE.name(), DeviceCheckStatus.MOUNT_FAILED, evt.details());
                            }
                        }
                    });

            return Flux.concat(
                    Flux.just(checkingEvent, agentStartEvent),
                    agentEvents
            );
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(ex -> {
            LOG.error("checkDriveStream error", ex);
            persistStatus(DeviceType.EXTERNAL_DRIVE.name(), DeviceCheckStatus.ERROR, ex.getMessage());
            return Flux.just(new DeviceCheckEvent(
                    DeviceType.EXTERNAL_DRIVE.name(),
                    DeviceCheckStatus.ERROR.name(),
                    "Nieoczekiwany błąd.",
                    ex.getMessage(),
                    true
            ));
        });
    }

    public Flux<DeviceCheckEvent> checkIPhoneStream() {
        return Flux.defer(() -> {
            DeviceCheckEvent checkingEvent = new DeviceCheckEvent(
                    DeviceType.IPHONE.name(),
                    DeviceCheckStatus.CHECKING.name(),
                    "Uruchamiam wykrywanie iPhone'a...",
                    null,
                    false
            );

            var detectResult = hostAgent.detectIphone();

            if (detectResult.connected()) {
                DeviceCheckEvent trustCheckEvent = new DeviceCheckEvent(
                        DeviceType.IPHONE.name(),
                        DeviceCheckStatus.CHECKING.name(),
                        "Sprawdzam zaufanie urządzenia...",
                        null,
                        false
                );

                hostAgent.iphoneCheckTrust(detectResult.udid());

                DeviceCheckEvent mountingEvent = new DeviceCheckEvent(
                        DeviceType.IPHONE.name(),
                        DeviceCheckStatus.MOUNTING.name(),
                        "Montuję iPhone...",
                        null,
                        false
                );

                var mountResult = hostAgent.iphoneMount(iphoneMountPath);
                String details = "connected=true, device_name=" + detectResult.deviceName() + ", udid=" + detectResult.udid();

                if (!mountResult.mounted()) {
                    LOG.warn("iPhone detected but mount failed: {}", mountResult.error());
                    String mountDetails = "mount_failed, error=" + mountResult.error();
                    DeviceCheckEvent failedEvent = new DeviceCheckEvent(
                            DeviceType.IPHONE.name(),
                            DeviceCheckStatus.MOUNT_FAILED.name(),
                            "iPhone wykryty, ale montowanie nie powiodło się.",
                            mountDetails,
                            true
                    );
                    persistStatus(DeviceType.IPHONE.name(), DeviceCheckStatus.MOUNT_FAILED, mountDetails);
                    return Flux.just(checkingEvent, trustCheckEvent, mountingEvent, failedEvent);
                }

                String stepDesc = "iPhone podłączony i zamontowany"
                        + (detectResult.deviceName() != null ? ": " + detectResult.deviceName() : "");
                DeviceCheckEvent finalEvent = new DeviceCheckEvent(
                        DeviceType.IPHONE.name(),
                        DeviceCheckStatus.CONNECTED.name(),
                        stepDesc,
                        details,
                        true
                );
                persistStatus(DeviceType.IPHONE.name(), DeviceCheckStatus.CONNECTED, details);
                return Flux.just(checkingEvent, trustCheckEvent, mountingEvent, finalEvent);
            }

            DeviceCheckEvent finalEvent = new DeviceCheckEvent(
                    DeviceType.IPHONE.name(),
                    DeviceCheckStatus.DISCONNECTED.name(),
                    "Nie wykryto iPhone'a.",
                    "connected=false",
                    true
            );
            persistStatus(DeviceType.IPHONE.name(), DeviceCheckStatus.DISCONNECTED, "connected=false");
            return Flux.just(checkingEvent, finalEvent);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(ex -> {
            LOG.error("checkIPhoneStream error", ex);
            persistStatus(DeviceType.IPHONE.name(), DeviceCheckStatus.ERROR, ex.getMessage());
            return Flux.just(new DeviceCheckEvent(
                    DeviceType.IPHONE.name(),
                    DeviceCheckStatus.ERROR.name(),
                    "Nieoczekiwany błąd.",
                    ex.getMessage(),
                    true
            ));
        });
    }

    public Flux<DeviceCheckEvent> checkICloudStream() {
        return Flux.defer(() -> {
            DeviceCheckEvent checkingEvent = new DeviceCheckEvent(
                    DeviceType.ICLOUD.name(),
                    DeviceCheckStatus.CHECKING.name(),
                    "Sprawdzam dostępność icloud-service...",
                    null,
                    false
            );

            try {
                iCloudServiceClient.health();
            } catch (Exception ex) {
                LOG.warn("icloud-service unreachable", ex);
                persistStatus(DeviceType.ICLOUD.name(), DeviceCheckStatus.UNREACHABLE, ex.getMessage());
                return Flux.just(
                        checkingEvent,
                        new DeviceCheckEvent(
                                DeviceType.ICLOUD.name(),
                                DeviceCheckStatus.UNREACHABLE.name(),
                                "icloud-service niedostępny.",
                                ex.getMessage(),
                                true
                        )
                );
            }

            DeviceCheckEvent reachableEvent = new DeviceCheckEvent(
                    DeviceType.ICLOUD.name(),
                    DeviceCheckStatus.CHECKING.name(),
                    "Serwis dostępny. Sprawdzam aktywne sesje...",
                    null,
                    false
            );

            boolean hasSessions = false;
            String sessionsDetails = null;
            try {
                var sessionsResponse = iCloudServiceClient.listSessions();
                List<Map<String, Object>> body = sessionsResponse.body();
                sessionsDetails = summarizeSessions(body);
                hasSessions = body != null && body.stream()
                        .anyMatch(s -> Boolean.TRUE.equals(s.get("active")));
            } catch (Exception ex) {
                LOG.warn("Failed to list icloud sessions", ex);
            }

            DeviceCheckStatus finalStatus = hasSessions ? DeviceCheckStatus.CONNECTED : DeviceCheckStatus.DISCONNECTED;
            DeviceCheckEvent finalEvent = new DeviceCheckEvent(
                    DeviceType.ICLOUD.name(),
                    finalStatus.name(),
                    hasSessions ? "Znaleziono aktywne sesje iCloud." : "Brak aktywnych sesji iCloud.",
                    sessionsDetails,
                    true
            );
            persistStatus(DeviceType.ICLOUD.name(), finalStatus, sessionsDetails);
            return Flux.just(checkingEvent, reachableEvent, finalEvent);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(ex -> {
            LOG.error("checkICloudStream error", ex);
            persistStatus(DeviceType.ICLOUD.name(), DeviceCheckStatus.ERROR, ex.getMessage());
            return Flux.just(new DeviceCheckEvent(
                    DeviceType.ICLOUD.name(),
                    DeviceCheckStatus.ERROR.name(),
                    "Nieoczekiwany błąd.",
                    ex.getMessage(),
                    true
            ));
        });
    }

    private DeviceCheckEvent mapAgentStep(AgentStepEvent agentStep) {
        boolean isFinal = "final".equals(String.valueOf(agentStep.step()));
        String status = isFinal
                ? (Boolean.TRUE.equals(agentStep.success()) ? DeviceCheckStatus.CONNECTED.name() : DeviceCheckStatus.MOUNT_FAILED.name())
                : DeviceCheckStatus.MOUNTING.name();
        String desc = agentStep.action() != null
                ? "Agent: " + agentStep.action()
                : agentStep.message();
        return new DeviceCheckEvent(DeviceType.EXTERNAL_DRIVE.name(), status, desc, agentStep.result(), isFinal);
    }

    private void persistStatus(String deviceType, DeviceCheckStatus status, String details) {
        try {
            DeviceStatus entity = deviceStatusRepository.findByDeviceType(deviceType)
                    .orElseGet(() -> {
                        DeviceStatus s = new DeviceStatus();
                        s.setId("device-" + deviceType.toLowerCase().replace("_", "-"));
                        s.setDeviceType(deviceType);
                        return s;
                    });

            entity.setStatus(status.name());
            entity.setConnected(status == DeviceCheckStatus.CONNECTED);
            entity.setLastCheckedAt(Instant.now());
            entity.setDetails(details);

            if (deviceStatusRepository.existsById(entity.getId())) {
                deviceStatusRepository.update(entity);
            } else {
                deviceStatusRepository.save(entity);
            }
        } catch (Exception ex) {
            LOG.error("Failed to persist device status for {}", deviceType, ex);
        }
    }

    private String summarizeSessions(List<Map<String, Object>> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return "Brak aktywnych sesji.";
        }
        List<Map<String, Object>> active = sessions.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("active")))
                .toList();
        if (active.isEmpty()) {
            return "Brak aktywnych sesji (łącznie " + sessions.size() + ").";
        }
        String appleIds = active.stream()
                .map(s -> String.valueOf(s.getOrDefault("apple_id", "unknown")))
                .distinct()
                .collect(Collectors.joining(", "));
        return "Aktywne sesje: " + active.size() + " (" + appleIds + ")";
    }

    private String extractJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\": \"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    public Map<String, Object> unmountIPhone() {
        var result = hostAgent.iphoneUnmount(iphoneMountPath);
        if (result.unmounted()) {
            persistStatus(DeviceType.IPHONE.name(), DeviceCheckStatus.DISCONNECTED, "Manually unmounted");
        }
        Map<String, Object> response = new HashMap<>();
        response.put("unmounted", result.unmounted());
        response.put("error", result.error());
        return response;
    }

    private DeviceStatusResponse toResponse(DeviceStatus status) {
        Boolean mounted = null;
        if ("IPHONE".equals(status.getDeviceType()) && status.isConnected()) {
            mounted = Files.isDirectory(Path.of(iphoneMountPath, "DCIM"));
        }
        return new DeviceStatusResponse(
                status.getId(),
                status.getDeviceType(),
                status.getStatus(),
                status.isConnected(),
                status.getLastCheckedAt(),
                status.getDetails(),
                mounted
        );
    }
}
