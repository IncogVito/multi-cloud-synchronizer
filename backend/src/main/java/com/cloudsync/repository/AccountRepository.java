package com.cloudsync.repository;

import com.cloudsync.model.entity.ICloudAccount;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@JdbcRepository(dialect = Dialect.H2)
public interface AccountRepository extends CrudRepository<ICloudAccount, String> {

    Optional<ICloudAccount> findByAppleId(String appleId);

    boolean existsByAppleId(String appleId);
}
