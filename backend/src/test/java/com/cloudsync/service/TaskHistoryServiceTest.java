package com.cloudsync.service;

import com.cloudsync.model.dto.TaskHistoryPageDto;
import com.cloudsync.model.entity.TaskHistoryEntity;
import com.cloudsync.repository.TaskHistoryRepository;
import com.cloudsync.repository.TaskItemRepository;
import com.cloudsync.repository.TaskSyncPhaseRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskHistoryServiceTest {

    private TaskHistoryRepository taskHistoryRepository;
    private TaskHistoryService service;

    @BeforeEach
    void setUp() {
        taskHistoryRepository = mock(TaskHistoryRepository.class);
        TaskSyncPhaseRepository phaseRepo = mock(TaskSyncPhaseRepository.class);
        TaskItemRepository itemRepo = mock(TaskItemRepository.class);
        service = new TaskHistoryService(taskHistoryRepository, phaseRepo, itemRepo);

        when(taskHistoryRepository.listFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
    }

    @Test
    void listHistory_filtersByAccountId() {
        service.listHistory(0, 20, null, null, "acc1");

        verify(taskHistoryRepository).listFiltered(eq("acc1"), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void listHistory_passesAllFilters() {
        service.listHistory(0, 20, "SYNC", "RUNNING", "acc1");

        verify(taskHistoryRepository).listFiltered(eq("acc1"), eq("SYNC"), eq("RUNNING"), any(Pageable.class));
    }

    @Test
    void listHistory_nullAccountId_returnsAll() {
        service.listHistory(0, 20, null, null, null);

        verify(taskHistoryRepository).listFiltered(isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void listHistory_excludesOtherAccounts() {
        TaskHistoryEntity acc1Task = entity("t1", "acc1");
        when(taskHistoryRepository.listFiltered(eq("acc1"), any(), any(), any(Pageable.class)))
                .thenReturn(Page.of(List.of(acc1Task), Pageable.from(0, 20), 1L));

        TaskHistoryPageDto result = service.listHistory(0, 20, null, null, "acc1");

        ArgumentCaptor<String> accountCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskHistoryRepository).listFiltered(accountCaptor.capture(), isNull(), isNull(), any(Pageable.class));
        assertThat(accountCaptor.getValue()).isEqualTo("acc1");
        assertThat(result.tasks()).allMatch(t -> t.accountId().equals("acc1"));
    }

    private TaskHistoryEntity entity(String id, String accountId) {
        TaskHistoryEntity e = new TaskHistoryEntity();
        e.setId(id);
        e.setType("SYNC");
        e.setAccountId(accountId);
        e.setProvider("icloud");
        e.setStatus("COMPLETED");
        e.setCreatedAt(Instant.now());
        return e;
    }
}
