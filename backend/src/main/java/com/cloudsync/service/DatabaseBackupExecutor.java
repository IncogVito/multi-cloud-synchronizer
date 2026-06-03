package com.cloudsync.service;

import io.micronaut.data.connection.annotation.Connectable;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

@Singleton
class DatabaseBackupExecutor {

    private final DataSource dataSource;

    DatabaseBackupExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Connectable
    void vacuum(Path backupFile) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + backupFile.toString().replace("'", "''") + "'");
        }
    }
}
