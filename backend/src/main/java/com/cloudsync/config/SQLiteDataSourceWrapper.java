package com.cloudsync.config;

import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.configuration.jdbc.hikari.DatasourceConfiguration;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@Singleton
public class SQLiteDataSourceWrapper implements BeanCreatedEventListener<DataSource> {

    private static final Logger log = LoggerFactory.getLogger(SQLiteDataSourceWrapper.class);

    private final DatasourceConfiguration datasourceConfig;

    public SQLiteDataSourceWrapper(@Named("default") DatasourceConfiguration datasourceConfig) {
        this.datasourceConfig = datasourceConfig;
    }

    @Override
    public DataSource onCreated(@NonNull BeanCreatedEvent<DataSource> event) {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setTempStore(SQLiteConfig.TempStore.MEMORY);
        config.setCacheSize(-32000);
        config.setBusyTimeout(5000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
        config.setLockingMode(SQLiteConfig.LockingMode.NORMAL);
        config.setSharedCache(false);

        CustomSQLiteDataSource dataSource = new CustomSQLiteDataSource(config);
        dataSource.setUrl(datasourceConfig.getUrl());

        if (event.getBean() instanceof HikariDataSource hds) {
            hds.close();
        }

        log.debug("Replaced DataSource {} with a custom SQLiteDataSource", event.getBean());

        return dataSource;
    }

    private static class CustomSQLiteDataSource extends SQLiteDataSource {
        public CustomSQLiteDataSource(SQLiteConfig config) {
            super(config);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrapConnection(super.getConnection());
        }

        private Connection wrapConnection(Connection realConnection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{ Connection.class },
                    new ConnectionProxy(realConnection)
            );
        }
    }

    private record ConnectionProxy(Connection realConnection) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            log.trace("Invoking {}({}) on {}", methodName, args, realConnection);

            if ("setReadOnly".equals(methodName)) {
                return null;
            }

            try {
                return method.invoke(realConnection, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                log.trace("Invocation of {}({}) on {} resulted in exception: {}", methodName, args, realConnection, cause.getMessage());
                throw cause;
            }
        }
    }
}
