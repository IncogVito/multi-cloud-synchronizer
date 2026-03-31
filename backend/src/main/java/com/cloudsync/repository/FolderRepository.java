package com.cloudsync.repository;

import com.cloudsync.model.entity.VirtualFolder;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.H2)
public interface FolderRepository extends CrudRepository<VirtualFolder, String> {

    List<VirtualFolder> findByParentIdIsNull();

    List<VirtualFolder> findByParentId(String parentId);

    List<VirtualFolder> findByFolderType(String folderType);

    boolean existsByNameAndParentId(String name, String parentId);
}
