package com.cloudsync.controller;

import com.cloudsync.model.dto.DeletionJobRequest;
import com.cloudsync.model.dto.DeletionJobStartResponse;
import com.cloudsync.model.dto.DeletionProgress;
import com.cloudsync.service.DeletionJob;
import com.cloudsync.service.DeletionJobService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DeletionJobControllerTest {

    private DeletionJobService service;
    private DeletionJobController controller;

    @BeforeEach
    void setUp() {
        service = mock(DeletionJobService.class);
        controller = new DeletionJobController(service);
    }

    @Test
    void startDeletionJob_delegatesToServiceAndReturnsResponse() {
        var request = new DeletionJobRequest("acc1", List.of("p1", "p2"), "ICLOUD");
        var expected = new DeletionJobStartResponse("job-123", 2, 0);
        when(service.startJob("acc1", List.of("p1", "p2"), "ICLOUD")).thenReturn(expected);

        DeletionJobStartResponse result = controller.startDeletionJob(request);

        assertThat(result.jobId()).isEqualTo("job-123");
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(0);
        verify(service).startJob("acc1", List.of("p1", "p2"), "ICLOUD");
    }

    @Test
    void getDeletionJob_returnsCurrentProgressWhenJobExists() {
        DeletionJob job = new DeletionJob("job-1", "acc1", "ICLOUD", List.of("p1"));
        when(service.getJob("job-1")).thenReturn(Optional.of(job));

        DeletionProgress progress = controller.getDeletionJob("job-1");

        assertThat(progress.total()).isEqualTo(1);
        assertThat(progress.deleted()).isEqualTo(0);
        assertThat(progress.done()).isFalse();
    }

    @Test
    void getDeletionJob_throws404WhenJobNotFound() {
        when(service.getJob("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getDeletionJob("missing"))
                .isInstanceOf(HttpStatusException.class)
                .extracting(ex -> ((HttpStatusException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getDeletionJobProgress_returns404WhenJobNotFound() {
        when(service.getJob("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getDeletionJobProgress("missing"))
                .isInstanceOf(HttpStatusException.class)
                .extracting(ex -> ((HttpStatusException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getDeletionJobProgress_returnsSsePublisherWhenJobExists() {
        DeletionJob job = new DeletionJob("job-1", "acc1", "ICLOUD", List.of("p1"));
        when(service.getJob("job-1")).thenReturn(Optional.of(job));

        var publisher = controller.getDeletionJobProgress("job-1");

        assertThat(publisher).isNotNull();
    }

    @Test
    void cancelDeletionJob_delegatesToService() {
        var response = controller.cancelDeletionJob("job-1");

        verify(service).cancelJob("job-1");
        assertThat(response.getStatus().getCode()).isEqualTo(HttpStatus.NO_CONTENT.getCode());
    }

    @Test
    void startDeletionJob_withSkippedPhotos_returnsCorrectCounts() {
        var request = new DeletionJobRequest("acc1", List.of("p1", "p2", "p3"), "ICLOUD");
        var expected = new DeletionJobStartResponse("job-456", 1, 2);
        when(service.startJob("acc1", List.of("p1", "p2", "p3"), "ICLOUD")).thenReturn(expected);

        DeletionJobStartResponse result = controller.startDeletionJob(request);

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
    }
}
