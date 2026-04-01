package com.cloudsync.service;

import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.model.dto.AgentStepEvent;
import com.cloudsync.model.dto.DeviceCheckEvent;
import com.cloudsync.model.dto.DeviceStatusResponse;
import com.cloudsync.model.entity.DeviceStatus;
import com.cloudsync.model.enums.DeviceCheckStatus;
import com.cloudsync.model.enums.DeviceType;
import com.cloudsync.repository.DeviceStatusRepository;
import com.cloudsync.util.ShellExecutor;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final DiskDetectionAgent diskDetectionAgent;
    private final ICloudServiceClient iCloudServiceClient;

    @Value("${EXTERNAL_DRIVE_PATH:/mnt/external-drive}")
    private String externalDrivePath;

    @Value("${app.scripts-dir:/scripts}")
    private String scriptsDir;

    public DeviceStatusService(DeviceStatusRepository deviceStatusRepository,
                               ShellExecutor shellExecutor,
                               DiskDetectionAgent diskDetectionAgent,
                               ICloudServiceClient iCloudServiceClient) {
        this.deviceStatusRepository = deviceStatusRepository;
        this.shellExecutor = shellExecutor;
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
                    "Sprawdzam punkt montowania " + externalDrivePath + "...",
                    null,
                    false
            );

            boolean mounted = Files.isDirectory(Path.of(externalDrivePath));

            if (mounted) {
                DeviceCheckEvent finalEvent = new DeviceCheckEvent(
                        DeviceType.EXTERNAL_DRIVE.name(),
                        DeviceCheckStatus.CONNECTED.name(),
                        "Dysk dostępny.",
                        null,
                        true
                );
                persistStatus(DeviceType.EXTERNAL_DRIVE.name(), DeviceCheckStatus.CONNECTED, null);
                return Flux.just(checkingEvent, finalEvent);
            }

            DeviceCheckEvent agentStartEvent = new DeviceCheckEvent(
                    DeviceType.EXTERNAL_DRIVE.name(),
                    DeviceCheckStatus.CHECKING.name(),
                    "Dysk nie zamontowany. Uruchamiam agenta wykrywania...",
                    null,
                    false
            );

            Flux<DeviceCheckEvent> agentEvents = diskDetectionAgent.detectDrive()
                    .map(this::mapAgentStep)
                    .doOnNext(evt -> {
                        if (evt.terminal()) {
                            DeviceCheckStatus finalStatus = DeviceCheckStatus.CONNECTED.name().equals(evt.status())
                                    ? DeviceCheckStatus.CONNECTED
                                    : DeviceCheckStatus.MOUNT_FAILED;
                            persistStatus(DeviceType.EXTERNAL_DRIVE.name(), finalStatus, evt.details());
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
                    "Uruchamiam skrypt wykrywania iPhone'a...",
                    null,
                    false
            );

            ShellExecutor.ShellResult result = shellExecutor.executeScript(scriptsDir, "detect-iphone.sh");

            if (result.isSuccess() && result.stdout().contains("\"connected\": true")) {
                DeviceCheckEvent trustCheckEvent = new DeviceCheckEvent(
                        DeviceType.IPHONE.name(),
                        DeviceCheckStatus.CHECKING.name(),
                        "Sprawdzam zaufanie urządzenia...",
                        null,
                        false
                );

                ShellExecutor.ShellResult trustResult = shellExecutor.executeScript(scriptsDir, "iphone-check-trust.sh");
                String details = result.stdout();

                String deviceName = extractJsonField(result.stdout(), "device_name");
                String stepDesc = "iPhone podłączony" + (deviceName != null ? ": " + deviceName : "");

                DeviceCheckEvent finalEvent = new DeviceCheckEvent(
                        DeviceType.IPHONE.name(),
                        DeviceCheckStatus.CONNECTED.name(),
                        stepDesc,
                        details,
                        true
                );
                persistStatus(DeviceType.IPHONE.name(), DeviceCheckStatus.CONNECTED, details);
                return Flux.just(checkingEvent, trustCheckEvent, finalEvent);
            }

            DeviceCheckEvent finalEvent = new DeviceCheckEvent(
                    DeviceType.IPHONE.name(),
                    DeviceCheckStatus.DISCONNECTED.name(),
                    "Nie wykryto iPhone'a.",
                    result.stdout(),
                    true
            );
            persistStatus(DeviceType.IPHONE.name(), DeviceCheckStatus.DISCONNECTED, result.stdout());
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
                Object body = sessionsResponse.body();
                sessionsDetails = body != null ? body.toString() : null;
                hasSessions = body != null && !body.toString().equals("[]") && !body.toString().equals("{}");
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

    private DeviceStatusResponse toResponse(DeviceStatus status) {
        return new DeviceStatusResponse(
                status.getId(),
                status.getDeviceType(),
                status.getStatus(),
                status.isConnected(),
                status.getLastCheckedAt(),
                status.getDetails()
        );
    }
}
