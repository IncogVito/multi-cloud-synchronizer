package com.cloudsync.repository;

import com.cloudsync.model.entity.Photo;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.H2)
public interface PhotoRepository extends PageableRepository<Photo, String> {

    List<Photo> findByAccountId(String accountId);

    Page<Photo> findByAccountId(String accountId, Pageable pageable);

    Page<Photo> findBySyncedToDisk(boolean syncedToDisk, Pageable pageable);

    List<Photo> findByAccountIdAndSyncedToDisk(String accountId, boolean syncedToDisk);

    Optional<Photo> findByIcloudPhotoId(String icloudPhotoId);

    long countByAccountId(String accountId);

    @Query("SELECT * FROM photos WHERE account_id = :accountId AND synced_to_disk = :synced")
    List<Photo> findByAccountIdAndSynced(String accountId, boolean synced);

    List<Photo> findByAccountIdAndSyncStatus(String accountId, String syncStatus);

    long countByAccountIdAndSyncStatus(String accountId, String syncStatus);
}
