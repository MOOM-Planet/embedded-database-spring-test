/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.zonky.test.db.provider.impl;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.zonky.test.db.flyway.BlockingDataSourceWrapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.zonky.test.db.provider.DatabasePreparer;
import io.zonky.test.db.provider.DatabaseProvider;
import io.zonky.test.db.provider.DatabaseType;
import io.zonky.test.db.provider.ProviderType;
import io.zonky.test.db.provider.impl.ZonkyPostgresDatabaseProvider.DatabaseInstance.DatabaseTemplate;
import io.zonky.test.db.util.PropertyUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;

public class ZonkyPostgresDatabaseProvider implements DatabaseProvider {

    private static final LoadingCache<ClusterKey, DatabaseInstance> databases = CacheBuilder.newBuilder()
            .build(new CacheLoader<ClusterKey, DatabaseInstance>() {
                public DatabaseInstance load(ClusterKey key) throws IOException {
                    return new DatabaseInstance(key.databaseConfig);
                }
            });

    private final DatabaseConfig databaseConfig;
    private final ClientConfig clientConfig;

    public ZonkyPostgresDatabaseProvider(Environment environment, ObjectProvider<List<Consumer<EmbeddedPostgres.Builder>>> databaseCustomizers) {
        String preparerIsolation = environment.getProperty("zonky.test.database.postgres.zonky-provider.preparer-isolation", "database");
        PreparerIsolation isolation = PreparerIsolation.valueOf(preparerIsolation.toUpperCase());

        Map<String, String> initdbProperties = PropertyUtils.extractAll(environment, "zonky.test.database.postgres.initdb.properties");
        Map<String, String> configProperties = PropertyUtils.extractAll(environment, "zonky.test.database.postgres.server.properties");
        Map<String, String> connectProperties = PropertyUtils.extractAll(environment, "zonky.test.database.postgres.client.properties");

        List<Consumer<EmbeddedPostgres.Builder>> customizers = Optional.ofNullable(databaseCustomizers.getIfAvailable()).orElse(emptyList());

        this.databaseConfig = new DatabaseConfig(initdbProperties, configProperties, customizers, isolation);
        this.clientConfig = new ClientConfig(connectProperties);
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.POSTGRES;
    }

    @Override
    public ProviderType getProviderType() {
        return ProviderType.ZONKY;
    }

    @Override
    public DataSource getDatabase(DatabasePreparer preparer) throws SQLException {
        ClusterKey clusterKey = new ClusterKey(databaseConfig, clientConfig, preparer);
        DatabaseInstance instance = databases.getUnchecked(clusterKey);
        DatabaseTemplate template = instance.getTemplate(clientConfig, preparer);
        return template.createDatabase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ZonkyPostgresDatabaseProvider that = (ZonkyPostgresDatabaseProvider) o;
        return Objects.equals(databaseConfig, that.databaseConfig) &&
                Objects.equals(clientConfig, that.clientConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseConfig, clientConfig);
    }

    protected static class DatabaseInstance {

        private final EmbeddedPostgres postgres;
        private final Semaphore semaphore;

        private final LoadingCache<TemplateKey, DatabaseTemplate> templates = CacheBuilder.newBuilder()
                .build(new CacheLoader<TemplateKey, DatabaseTemplate>() {
                    public DatabaseTemplate load(TemplateKey key) throws Exception {
                        return new DatabaseTemplate(key.config, key.preparer);
                    }
                });

        private DatabaseInstance(DatabaseConfig config) throws IOException {
            EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder();
            config.applyTo(builder);

            postgres = builder.start();

            DataSource dataSource = postgres.getDatabase("postgres", "postgres");
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            Integer maxConnections = jdbcTemplate.queryForObject("show max_connections", Integer.class);

            semaphore = new Semaphore(maxConnections);
        }

        public DatabaseTemplate getTemplate(ClientConfig config, DatabasePreparer preparer) {
            return templates.getUnchecked(new TemplateKey(config, preparer));
        }

        protected class DatabaseTemplate {

            private final ClientConfig config;
            private final String templateName;

            private DatabaseTemplate(ClientConfig config, DatabasePreparer preparer) throws SQLException {
                this.config = config;
                this.templateName = RandomStringUtils.randomAlphabetic(12).toLowerCase(Locale.ENGLISH);

                executeStatement(String.format("CREATE DATABASE %s OWNER %s ENCODING 'utf8'", templateName, "postgres"));
                DataSource dataSource = getDatabase(templateName);
                preparer.prepare(dataSource);
            }

            public DataSource createDatabase() throws SQLException {
                String databaseName = RandomStringUtils.randomAlphabetic(12).toLowerCase(Locale.ENGLISH);
                executeStatement(String.format("CREATE DATABASE %s TEMPLATE %s OWNER %s ENCODING 'utf8'", databaseName, templateName, "postgres"));
                return getDatabase(databaseName);
            }

            private void executeStatement(String ddlStatement) throws SQLException {
                DataSource dataSource = getDatabase("postgres");
                try (Connection connection = dataSource.getConnection(); PreparedStatement stmt = connection.prepareStatement(ddlStatement)) {
                    stmt.execute();
                }
            }

            private DataSource getDatabase(String dbName) {
                DataSource dataSource = postgres.getDatabase("postgres", dbName, config.connectProperties);
                return new BlockingDataSourceWrapper(dataSource, semaphore);
            }
        }

        protected static class TemplateKey {

            private final ClientConfig config;
            private final DatabasePreparer preparer;

            private TemplateKey(ClientConfig config, DatabasePreparer preparer) {
                this.config = config;
                this.preparer = preparer;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                TemplateKey that = (TemplateKey) o;
                return Objects.equals(config, that.config) &&
                        Objects.equals(preparer, that.preparer);
            }

            @Override
            public int hashCode() {
                return Objects.hash(config, preparer);
            }
        }
    }

    private static class DatabaseConfig {

        private final Map<String, String> initdbProperties;
        private final Map<String, String> configProperties;
        private final List<Consumer<EmbeddedPostgres.Builder>> customizers;
        private final EmbeddedPostgres.Builder builder;
        private final PreparerIsolation isolation;

        private DatabaseConfig(Map<String, String> initdbProperties, Map<String, String> configProperties, List<Consumer<EmbeddedPostgres.Builder>> customizers, PreparerIsolation isolation) {
            this.initdbProperties = ImmutableMap.copyOf(initdbProperties);
            this.configProperties = ImmutableMap.copyOf(configProperties);
            this.customizers = ImmutableList.copyOf(customizers);
            this.isolation = isolation;
            this.builder = EmbeddedPostgres.builder();
            applyTo(this.builder);
        }

        public final void applyTo(EmbeddedPostgres.Builder builder) {
            builder.setServerConfig("max_connections", "300");
            builder.setPGStartupWait(Duration.ofSeconds(20L));
            initdbProperties.forEach(builder::setLocaleConfig);
            configProperties.forEach(builder::setServerConfig);
            customizers.forEach(c -> c.accept(builder));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DatabaseConfig that = (DatabaseConfig) o;
            return Objects.equals(builder, that.builder) &&
                    Objects.equals(isolation, that.isolation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(builder, isolation);
        }
    }

    private static class ClientConfig {

        private final Map<String, String> connectProperties;

        private ClientConfig(Map<String, String> connectProperties) {
            this.connectProperties = ImmutableMap.copyOf(connectProperties);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClientConfig that = (ClientConfig) o;
            return Objects.equals(connectProperties, that.connectProperties);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connectProperties);
        }
    }

    private static class ClusterKey {

        private final DatabaseConfig databaseConfig;
        private final ClientConfig clientConfig;
        private final DatabasePreparer preparer;

        private ClusterKey(DatabaseConfig databaseConfig, ClientConfig clientConfig, DatabasePreparer preparer) {
            this.databaseConfig = databaseConfig;

            if (databaseConfig.isolation == PreparerIsolation.CLUSTER) {
                this.clientConfig = clientConfig;
                this.preparer = preparer;
            } else {
                this.clientConfig = null;
                this.preparer = null;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClusterKey that = (ClusterKey) o;
            return Objects.equals(databaseConfig, that.databaseConfig) &&
                    Objects.equals(clientConfig, that.clientConfig) &&
                    Objects.equals(preparer, that.preparer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(databaseConfig, clientConfig, preparer);
        }
    }

    private enum PreparerIsolation {

        /**
         * All databases are stored within a single database cluster.
         */
        DATABASE,

        /**
         * A new database cluster is created for each template database.
         */
        CLUSTER

    }
}
