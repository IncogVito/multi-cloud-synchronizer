package com.cloudsync.repository;

import com.cloudsync.model.entity.AppContextEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

@JdbcRepository(dialect = Dialect.H2)
public interface AppContextRepository extends CrudRepository<AppContextEntity, Integer> {
}
