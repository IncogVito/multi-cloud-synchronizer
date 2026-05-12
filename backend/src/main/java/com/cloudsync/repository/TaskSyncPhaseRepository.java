package com.cloudsync.repository;

import com.cloudsync.model.entity.TaskSyncPhaseEntity;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.H2)
public interface TaskSyncPhaseRepository extends CrudRepository<TaskSyncPhaseEntity, Long> {

    List<TaskSyncPhaseEntity> findByTaskIdOrderByStartedAt(String taskId);

    void deleteByTaskId(String taskId);
}
