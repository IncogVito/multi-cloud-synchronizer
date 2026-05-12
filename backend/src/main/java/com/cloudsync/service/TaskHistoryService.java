package com.cloudsync.service;

import com.cloudsync.model.dto.TaskHistoryDetailDto;
import com.cloudsync.model.dto.TaskHistoryDto;
import com.cloudsync.model.dto.TaskHistoryPageDto;
import com.cloudsync.model.entity.TaskHistoryEntity;
import com.cloudsync.model.entity.TaskItemEntity;
import com.cloudsync.model.entity.TaskSyncPhaseEntity;
import com.cloudsync.repository.TaskHistoryRepository;
import com.cloudsync.repository.TaskItemRepository;
import com.cloudsync.repository.TaskSyncPhaseRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Singleton
public class TaskHistoryService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskHistoryService.class);

    private final TaskHistoryRepository taskHistoryRepository;
    private final TaskSyncPhaseRepository taskSyncPhaseRepository;
    private final TaskItemRepository taskItemRepository;

    public TaskHistoryService(
            TaskHistoryRepository taskHistoryRepository,
            TaskSyncPhaseRepository taskSyncPhaseRepository,
            TaskItemRepository taskItemRepository) {
        this.taskHistoryRepository = taskHistoryRepository;
        this.taskSyncPhaseRepository = taskSyncPhaseRepository;
        this.taskItemRepository = taskItemRepository;
    }

    public void createTask(String taskId, String type, String accountId, String provider, int totalItems) {
        try {
            TaskHistoryEntity entity = new TaskHistoryEntity();
            entity.setId(taskId);
            entity.setType(type);
            entity.setAccountId(accountId);
            entity.setProvider(provider);
            entity.setStatus("RUNNING");
            entity.setCreatedAt(Instant.now());
            entity.setTotalItems(totalItems);
            taskHistoryRepository.save(entity);
        } catch (Exception e) {
            LOG.error("Failed to persist task history create for {}: {}", taskId, e.getMessage());
        }
    }

    public void updateTaskProgress(String taskId, int succeeded, int failed) {
        try {
            taskHistoryRepository.findById(taskId).ifPresent(entity -> {
                entity.setSucceededItems(succeeded);
                entity.setFailedItems(failed);
                taskHistoryRepository.update(entity);
            });
        } catch (Exception e) {
            LOG.error("Failed to update task history progress for {}: {}", taskId, e.getMessage());
        }
    }

    public void completeTask(String taskId, String status, int succeeded, int failed, String errorMessage) {
        try {
            taskHistoryRepository.findById(taskId).ifPresent(entity -> {
                entity.setStatus(status);
                entity.setCompletedAt(Instant.now());
                entity.setSucceededItems(succeeded);
                entity.setFailedItems(failed);
                entity.setErrorMessage(errorMessage);
                taskHistoryRepository.update(entity);
            });
        } catch (Exception e) {
            LOG.error("Failed to complete task history for {}: {}", taskId, e.getMessage());
        }
    }

    public void recordSyncPhaseStart(String taskId, String phase) {
        try {
            closeOpenPhases(taskId, phase);
            TaskSyncPhaseEntity phaseEntity = new TaskSyncPhaseEntity();
            phaseEntity.setTaskId(taskId);
            phaseEntity.setPhase(phase);
            phaseEntity.setStartedAt(Instant.now());
            taskSyncPhaseRepository.save(phaseEntity);
        } catch (Exception e) {
            LOG.error("Failed to record sync phase start {}/{}: {}", taskId, phase, e.getMessage());
        }
    }

    public void recordSyncPhaseEnd(String taskId, String phase, String errorMessage) {
        try {
            List<TaskSyncPhaseEntity> phases = taskSyncPhaseRepository.findByTaskIdOrderByStartedAt(taskId);
            phases.stream()
                    .filter(p -> phase.equals(p.getPhase()) && p.getCompletedAt() == null)
                    .findFirst()
                    .ifPresent(p -> {
                        p.setCompletedAt(Instant.now());
                        p.setErrorMessage(errorMessage);
                        taskSyncPhaseRepository.update(p);
                    });
        } catch (Exception e) {
            LOG.error("Failed to record sync phase end {}/{}: {}", taskId, phase, e.getMessage());
        }
    }

    public void addTaskItems(String taskId, List<String> photoIds, List<String> photoNames, String itemStatus, String errorMessage) {
        try {
            for (int i = 0; i < photoIds.size(); i++) {
                TaskItemEntity item = new TaskItemEntity();
                item.setTaskId(taskId);
                item.setPhotoId(photoIds.get(i));
                item.setPhotoName(i < photoNames.size() ? photoNames.get(i) : null);
                item.setItemStatus(itemStatus);
                item.setErrorMessage(errorMessage);
                taskItemRepository.save(item);
            }
        } catch (Exception e) {
            LOG.error("Failed to add task items for {}: {}", taskId, e.getMessage());
        }
    }

    public TaskHistoryPageDto listHistory(int page, int size, String type, String status) {
        Pageable pageable = Pageable.from(page, size);
        Page<TaskHistoryEntity> result;

        if (type != null && status != null) {
            result = taskHistoryRepository.listByTypeAndStatus(type, status, pageable);
        } else if (type != null) {
            result = taskHistoryRepository.listByType(type, pageable);
        } else if (status != null) {
            result = taskHistoryRepository.listByStatus(status, pageable);
        } else {
            result = taskHistoryRepository.listAll(pageable);
        }

        List<TaskHistoryDto> dtos = result.getContent().stream().map(this::toDto).toList();
        return new TaskHistoryPageDto(dtos, result.getTotalSize(), result.getTotalPages(), page, size);
    }

    public Optional<TaskHistoryDetailDto> getDetail(String taskId) {
        return taskHistoryRepository.findById(taskId).map(entity -> {
            List<TaskSyncPhaseEntity> phases = taskSyncPhaseRepository.findByTaskIdOrderByStartedAt(taskId);
            List<TaskItemEntity> items = taskItemRepository.findByTaskId(taskId);

            List<TaskHistoryDetailDto.TaskSyncPhaseDto> phaseDtos = phases.stream()
                    .map(p -> new TaskHistoryDetailDto.TaskSyncPhaseDto(
                            p.getPhase(),
                            p.getStartedAt(),
                            p.getCompletedAt(),
                            p.getErrorMessage(),
                            durationMs(p.getStartedAt(), p.getCompletedAt())))
                    .toList();

            List<TaskHistoryDetailDto.TaskItemDto> itemDtos = items.stream()
                    .map(i -> new TaskHistoryDetailDto.TaskItemDto(
                            i.getItemStatus(),
                            i.getPhotoId(),
                            i.getPhotoName(),
                            i.getErrorMessage()))
                    .toList();

            return new TaskHistoryDetailDto(
                    entity.getId(),
                    entity.getType(),
                    entity.getAccountId(),
                    entity.getProvider(),
                    entity.getStatus(),
                    entity.getCreatedAt(),
                    entity.getCompletedAt(),
                    entity.getTotalItems(),
                    entity.getSucceededItems(),
                    entity.getFailedItems(),
                    entity.getErrorMessage(),
                    durationMs(entity.getCreatedAt(), entity.getCompletedAt()),
                    phaseDtos,
                    itemDtos);
        });
    }

    private TaskHistoryDto toDto(TaskHistoryEntity entity) {
        return new TaskHistoryDto(
                entity.getId(),
                entity.getType(),
                entity.getAccountId(),
                entity.getProvider(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getCompletedAt(),
                entity.getTotalItems(),
                entity.getSucceededItems(),
                entity.getFailedItems(),
                entity.getErrorMessage(),
                durationMs(entity.getCreatedAt(), entity.getCompletedAt()));
    }

    private Long durationMs(Instant start, Instant end) {
        if (start == null || end == null) return null;
        return end.toEpochMilli() - start.toEpochMilli();
    }

    private void closeOpenPhases(String taskId, String newPhase) {
        List<TaskSyncPhaseEntity> phases = taskSyncPhaseRepository.findByTaskIdOrderByStartedAt(taskId);
        phases.stream()
                .filter(p -> p.getCompletedAt() == null && !newPhase.equals(p.getPhase()))
                .forEach(p -> {
                    p.setCompletedAt(Instant.now());
                    taskSyncPhaseRepository.update(p);
                });
    }
}
