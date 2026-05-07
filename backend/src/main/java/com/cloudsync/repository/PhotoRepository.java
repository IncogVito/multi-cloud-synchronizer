package com.cloudsync.repository;

import com.cloudsync.model.entity.Photo;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.H2)
public interface PhotoRepository extends PageableRepository<Photo, String> {

    List<Photo> findByAccountId(String accountId);

    Page<Photo> findByAccountId(String accountId, Pageable pageable);

    @Query(value = "SELECT * FROM photos WHERE synced_to_disk = :syncedToDisk AND (deleted = false OR deleted IS NULL)",
           countQuery = "SELECT COUNT(*) FROM photos WHERE synced_to_disk = :syncedToDisk AND (deleted = false OR deleted IS NULL)")
    Page<Photo> findBySyncedToDisk(boolean syncedToDisk, Pageable pageable);

    @Query(value = "SELECT * FROM photos WHERE synced_to_disk = :syncedToDisk AND storage_device_id = :storageDeviceId AND (deleted = false OR deleted IS NULL)",
           countQuery = "SELECT COUNT(*) FROM photos WHERE synced_to_disk = :syncedToDisk AND storage_device_id = :storageDeviceId AND (deleted = false OR deleted IS NULL)")
    Page<Photo> findBySyncedToDiskAndStorageDeviceId(boolean syncedToDisk, String storageDeviceId, Pageable pageable);

    @Query("SELECT * FROM photos WHERE account_id = :accountId AND synced_to_disk = :syncedToDisk AND (deleted = false OR deleted IS NULL)")
    List<Photo> findByAccountIdAndSyncedToDisk(String accountId, boolean syncedToDisk);

    Optional<Photo> findByIcloudPhotoId(String icloudPhotoId);

    long countByAccountId(String accountId);

    @Query("SELECT * FROM photos WHERE account_id = :accountId AND synced_to_disk = :synced AND (deleted = false OR deleted IS NULL)")
    List<Photo> findByAccountIdAndSynced(String accountId, boolean synced);

    List<Photo> findByAccountIdAndSyncStatus(String accountId, String syncStatus);

    long countByAccountIdAndSyncStatus(String accountId, String syncStatus);

    @Query("SELECT COUNT(*) FROM photos WHERE account_id = :accountId AND sync_status = :syncStatus AND source_provider = :sourceProvider")
    long countByAccountIdAndSyncStatusAndSourceProvider(String accountId, String syncStatus, String sourceProvider);

    @Query("SELECT COUNT(*) FROM photos WHERE synced_to_disk = true AND storage_device_id = :storageDeviceId AND (deleted = false OR deleted IS NULL)")
    long countBySyncedToDiskAndStorageDeviceId(boolean syncedToDisk, String storageDeviceId);

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM photos WHERE synced_to_disk = true AND storage_device_id = :storageDeviceId AND (deleted = false OR deleted IS NULL)")
    Long sumFileSizeOnDisk(String storageDeviceId);

    long countByAccountIdAndExistsOnIcloud(String accountId, boolean existsOnIcloud);

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM photos WHERE account_id = :accountId AND exists_on_icloud = true")
    Long sumFileSizeOnIcloud(String accountId);

    long countByAccountIdAndExistsOnIphone(String accountId, Boolean existsOnIphone);

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM photos WHERE account_id = :accountId AND exists_on_iphone = true")
    Long sumFileSizeOnIphone(String accountId);

    @Query("SELECT COUNT(*) FROM photos WHERE storage_device_id = :storageDeviceId AND exists_on_icloud = true")
    long countByStorageDeviceIdAndExistsOnIcloud(String storageDeviceId);

    @Query("SELECT COUNT(*) FROM photos WHERE storage_device_id = :storageDeviceId AND exists_on_iphone = true")
    long countByStorageDeviceIdAndExistsOnIphone(String storageDeviceId);

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM photos WHERE storage_device_id = :storageDeviceId AND exists_on_icloud = true")
    Long sumFileSizeOnIcloudByDevice(String storageDeviceId);

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM photos WHERE storage_device_id = :storageDeviceId AND exists_on_iphone = true")
    Long sumFileSizeOnIphoneByDevice(String storageDeviceId);

    @Query("SELECT COUNT(*) FROM photos WHERE synced_to_disk = true AND (deleted = false OR deleted IS NULL) AND (thumbnail_path IS NULL OR thumbnail_path = '')")
    long countMissingThumbnails();

    @Query("SELECT COUNT(*) FROM photos WHERE synced_to_disk = true AND storage_device_id = :storageDeviceId AND (deleted = false OR deleted IS NULL) AND (thumbnail_path IS NULL OR thumbnail_path = '')")
    long countMissingThumbnailsByDevice(String storageDeviceId);

    @Query("SELECT * FROM photos WHERE synced_to_disk = true AND (deleted = false OR deleted IS NULL) AND (thumbnail_path IS NULL OR thumbnail_path = '')")
    List<Photo> findSyncedWithoutThumbnail();

    @Query("SELECT * FROM photos WHERE synced_to_disk = true AND storage_device_id = :storageDeviceId AND (deleted = false OR deleted IS NULL) AND (thumbnail_path IS NULL OR thumbnail_path = '')")
    List<Photo> findSyncedWithoutThumbnailByDevice(String storageDeviceId);

    @Query("SELECT * FROM photos WHERE storage_device_id = :storageDeviceId AND synced_to_disk = :syncedToDisk AND (deleted = false OR deleted IS NULL)")
    List<Photo> findByStorageDeviceIdAndSyncedToDisk(String storageDeviceId, boolean syncedToDisk);

    /** Includes deleted=true records — used for path deduplication during disk indexing. */
    @Query("SELECT * FROM photos WHERE storage_device_id = :storageDeviceId AND synced_to_disk = true")
    List<Photo> findAllByStorageDeviceIdAndSyncedToDiskIncludeDeleted(String storageDeviceId);

    /** All photos for device that have a file_path — used during reindex reconciliation. Includes deleted. */
    @Query("SELECT * FROM photos WHERE storage_device_id = :storageDeviceId AND file_path IS NOT NULL")
    List<Photo> findAllWithFilePathByStorageDeviceId(String storageDeviceId);

    /**
     * Returns a page of synced, non-deleted photos for a device whose createdDate falls within [startInclusive, endExclusive).
     */
    @Query(value = "SELECT * FROM photos WHERE synced_to_disk = :syncedToDisk AND storage_device_id = :storageDeviceId AND created_date >= :startInclusive AND created_date < :endExclusive AND (deleted = false OR deleted IS NULL)",
           countQuery = "SELECT COUNT(*) FROM photos WHERE synced_to_disk = :syncedToDisk AND storage_device_id = :storageDeviceId AND created_date >= :startInclusive AND created_date < :endExclusive AND (deleted = false OR deleted IS NULL)")
    Page<Photo> findBySyncedToDiskAndStorageDeviceIdAndCreatedDateBetween(
            boolean syncedToDisk,
            String storageDeviceId,
            Instant startInclusive,
            Instant endExclusive,
            Pageable pageable);

    Optional<Photo> findByFilePath(String filePath);

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    List<Photo> findByIdIn(List<String> ids);
}
