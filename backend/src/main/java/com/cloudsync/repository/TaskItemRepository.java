package com.cloudsync.repository;

import com.cloudsync.model.entity.TaskItemEntity;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.H2)
public interface TaskItemRepository extends CrudRepository<TaskItemEntity, Long> {

    List<TaskItemEntity> findByTaskId(String taskId);

    void deleteByTaskId(String taskId);
}
