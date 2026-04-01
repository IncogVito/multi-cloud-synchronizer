package com.cloudsync.repository;

import com.cloudsync.model.entity.StorageDevice;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@Repository
public interface StorageDeviceRepository extends CrudRepository<StorageDevice, String> {

    Optional<StorageDevice> findByFilesystemUuid(String filesystemUuid);
}
