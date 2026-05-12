package com.cloudsync.repository;

import com.cloudsync.model.entity.TaskHistoryEntity;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

@JdbcRepository(dialect = Dialect.H2)
public interface TaskHistoryRepository extends CrudRepository<TaskHistoryEntity, String> {

    @Query(value = "SELECT * FROM task_history ORDER BY created_at DESC",
           countQuery = "SELECT COUNT(*) FROM task_history")
    Page<TaskHistoryEntity> listAll(Pageable pageable);

    @Query(value = "SELECT * FROM task_history WHERE type = :type ORDER BY created_at DESC",
           countQuery = "SELECT COUNT(*) FROM task_history WHERE type = :type")
    Page<TaskHistoryEntity> listByType(String type, Pageable pageable);

    @Query(value = "SELECT * FROM task_history WHERE status = :status ORDER BY created_at DESC",
           countQuery = "SELECT COUNT(*) FROM task_history WHERE status = :status")
    Page<TaskHistoryEntity> listByStatus(String status, Pageable pageable);

    @Query(value = "SELECT * FROM task_history WHERE type = :type AND status = :status ORDER BY created_at DESC",
           countQuery = "SELECT COUNT(*) FROM task_history WHERE type = :type AND status = :status")
    Page<TaskHistoryEntity> listByTypeAndStatus(String type, String status, Pageable pageable);
}
