package com.cloudsync.repository;

import com.cloudsync.model.entity.AppContextEntity;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

@Repository
public interface AppContextRepository extends CrudRepository<AppContextEntity, Integer> {
}
