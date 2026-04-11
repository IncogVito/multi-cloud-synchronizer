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

    Page<Photo> findBySyncedToDiskAndStorageDeviceId(boolean syncedToDisk, String storageDeviceId, Pageable pageable);

    List<Photo> findByAccountIdAndSyncedToDisk(String accountId, boolean syncedToDisk);

    Optional<Photo> findByIcloudPhotoId(String icloudPhotoId);

    long countByAccountId(String accountId);

    @Query("SELECT * FROM photos WHERE account_id = :accountId AND synced_to_disk = :synced")
    List<Photo> findByAccountIdAndSynced(String accountId, boolean synced);

    List<Photo> findByAccountIdAndSyncStatus(String accountId, String syncStatus);

    long countByAccountIdAndSyncStatus(String accountId, String syncStatus);

    long countBySyncedToDiskAndStorageDeviceId(boolean syncedToDisk, String storageDeviceId);

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM photos WHERE synced_to_disk = true AND storage_device_id = :storageDeviceId")
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

    @Query("SELECT COUNT(*) FROM photos WHERE synced_to_disk = true AND (thumbnail_path IS NULL OR thumbnail_path = '')")
    long countMissingThumbnails();

    @Query("SELECT COUNT(*) FROM photos WHERE synced_to_disk = true AND storage_device_id = :storageDeviceId AND (thumbnail_path IS NULL OR thumbnail_path = '')")
    long countMissingThumbnailsByDevice(String storageDeviceId);

    @Query("SELECT * FROM photos WHERE synced_to_disk = true AND (thumbnail_path IS NULL OR thumbnail_path = '')")
    List<Photo> findSyncedWithoutThumbnail();

    @Query("SELECT * FROM photos WHERE synced_to_disk = true AND storage_device_id = :storageDeviceId AND (thumbnail_path IS NULL OR thumbnail_path = '')")
    List<Photo> findSyncedWithoutThumbnailByDevice(String storageDeviceId);
}
