package com.cloudsync.repository;

import com.cloudsync.model.entity.DeviceStatus;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@JdbcRepository(dialect = Dialect.H2)
public interface DeviceStatusRepository extends CrudRepository<DeviceStatus, String> {

    Optional<DeviceStatus> findByDeviceType(String deviceType);
}
