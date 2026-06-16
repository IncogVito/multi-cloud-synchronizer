package com.cloudsync.repository;

import com.cloudsync.model.entity.TaskHistoryEntity;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

@JdbcRepository(dialect = Dialect.H2)
public interface TaskHistoryRepository extends CrudRepository<TaskHistoryEntity, String> {

    @Query(value = "SELECT * FROM task_history "
            + "WHERE (:accountId IS NULL OR account_id = :accountId) "
            + "AND (:type IS NULL OR type = :type) "
            + "AND (:status IS NULL OR status = :status) "
            + "ORDER BY created_at DESC",
           countQuery = "SELECT COUNT(*) FROM task_history "
            + "WHERE (:accountId IS NULL OR account_id = :accountId) "
            + "AND (:type IS NULL OR type = :type) "
            + "AND (:status IS NULL OR status = :status)")
    Page<TaskHistoryEntity> listFiltered(@Nullable String accountId,
                                         @Nullable String type,
                                         @Nullable String status,
                                         Pageable pageable);
}
