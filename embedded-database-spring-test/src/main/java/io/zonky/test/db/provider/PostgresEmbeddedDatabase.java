package io.zonky.test.db.provider;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;

public class PostgresEmbeddedDatabase implements EmbeddedDatabase {

    private final PGSimpleDataSource dataSource;
    private final Map<String, String> aliases;

    public PostgresEmbeddedDatabase(PGSimpleDataSource dataSource, Map<String, String> aliases) {
        this.dataSource = dataSource;
        this.aliases = ImmutableMap.copyOf(aliases);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return dataSource.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() {
        return dataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        dataSource.setLogWriter(out);
    }

    @Override
    public int getLoginTimeout() {
        return dataSource.getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int seconds) {
        dataSource.setLoginTimeout(seconds);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return dataSource.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return dataSource.isWrapperFor(iface);
    }
    
    @Override
    public Logger getParentLogger() {
        return dataSource.getParentLogger();
    }

    @Override
    public String getServerName() {
        return dataSource.getServerName();
    }

    @Override
    public String getDatabaseName() {
        return dataSource.getDatabaseName();
    }

    @Override
    public String getUser() {
        return dataSource.getUser();
    }

    @Override
    public String getPassword() {
        return dataSource.getPassword();
    }

    @Override
    public int getPortNumber() {
        return dataSource.getPortNumber();
    }

    @Override
    public String getUrl() {
        String url = dataSource.getUrl() + String.format("?user=%s", getUser());

        if (StringUtils.isNotBlank(getPassword())) {
            url += String.format("&password=%s", getPassword());
        }

        return url;
    }

    @Override
    public Map<String, String> getAliases() {
        return aliases;
    }
}