package com.cloudsync.repository;

import com.cloudsync.model.entity.StorageDevice;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@JdbcRepository(dialect = Dialect.H2)
public interface StorageDeviceRepository extends CrudRepository<StorageDevice, String> {

    Optional<StorageDevice> findByFilesystemUuid(String filesystemUuid);

    Optional<StorageDevice> findByDevicePath(String devicePath);
}
