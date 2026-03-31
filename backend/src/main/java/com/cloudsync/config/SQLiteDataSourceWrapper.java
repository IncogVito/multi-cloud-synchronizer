package com.cloudsync.config;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Wraps the DataSource to return connections that silently ignore setReadOnly() calls.
 * SQLite's JDBC driver does not support changing the read-only flag after a connection
 * is established, but Micronaut Data JDBC sets it per-connection for read operations.
 */
@Singleton
public class SQLiteDataSourceWrapper implements BeanCreatedEventListener<DataSource> {

    @Override
    public DataSource onCreated(BeanCreatedEvent<DataSource> event) {
        DataSource original = event.getBean();
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return wrap(original.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return wrap(original.getConnection(username, password));
            }

            @Override
            public PrintWriter getLogWriter() throws SQLException {
                return original.getLogWriter();
            }

            @Override
            public void setLogWriter(PrintWriter out) throws SQLException {
                original.setLogWriter(out);
            }

            @Override
            public void setLoginTimeout(int seconds) throws SQLException {
                original.setLoginTimeout(seconds);
            }

            @Override
            public int getLoginTimeout() throws SQLException {
                return original.getLoginTimeout();
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                return original.getParentLogger();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                return original.unwrap(iface);
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) throws SQLException {
                return original.isWrapperFor(iface);
            }
        };
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                connection.getClass().getClassLoader(),
                new Class[]{Connection.class},
                new ReadOnlyIgnoringHandler(connection)
        );
    }

    private static class ReadOnlyIgnoringHandler implements InvocationHandler {
        private final Connection delegate;

        ReadOnlyIgnoringHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("setReadOnly".equals(method.getName())) {
                return null;
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
