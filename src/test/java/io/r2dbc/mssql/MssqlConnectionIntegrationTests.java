/*
 * Copyright 2018-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.mssql;

import io.r2dbc.mssql.util.IntegrationTestSupport;
import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.R2dbcPermissionDeniedException;
import io.r2dbc.spi.R2dbcTimeoutException;
import io.r2dbc.spi.R2dbcTransientException;
import io.r2dbc.spi.Result;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.r2dbc.spi.ConnectionFactoryOptions.LOCK_WAIT_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MssqlConnection} and {@link MssqlStatement}.
 *
 * @author Mark Paluch
 */
class MssqlConnectionIntegrationTests extends IntegrationTestSupport {

    @Test
    void shouldFailOnConnectionRefused() {

        MssqlConnectionConfiguration configuration = MssqlConnectionConfiguration.builder()
            .host(SERVER.getHost())
            .port(123)
            .username(SERVER.getUsername())
            .password(SERVER.getPassword())
            .build();

        MssqlConnectionFactory connectionFactory = new MssqlConnectionFactory(configuration);

        connectionFactory.create()
            .as(StepVerifier::create)
            .expectError(ConnectException.class)
            .verify();
    }

    @Test
    void shouldFailOnLoginFailedRefused() {

        MssqlConnectionConfiguration configuration = MssqlConnectionConfiguration.builder()
            .host(SERVER.getHost())
            .port(SERVER.getPort())
            .username(SERVER.getUsername())
            .password("foobar")
            .build();

        MssqlConnectionFactory connectionFactory = new MssqlConnectionFactory(configuration);

        connectionFactory.create()
            .as(StepVerifier::create)
            .expectError(R2dbcPermissionDeniedException.class)
            .verify();
    }

    @Test
    void shouldConnectWithSelfSignedCert() {

        MssqlConnectionConfiguration configuration = MssqlConnectionConfiguration.builder()
            .host(SERVER.getHost())
            .port(SERVER.getPort())
            .database("master")
            .username(SERVER.getUsername())
            .password(SERVER.getPassword())
            .enableSsl()
            .trustServerCertificate()
            .build();

        MssqlConnectionFactory connectionFactory = new MssqlConnectionFactory(configuration);

        Flux.usingWhen(connectionFactory.create(), conn -> conn.createStatement("SELECT @@VERSION").execute()
            .flatMap(it -> it.map((row, rowMetadata) -> row.get(0))), MssqlConnection::close)
            .as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();
    }

    @Test
    void shouldReportMetadata() throws Exception {

        try (Connection connection = SERVER.getDataSource().getConnection()) {

            DatabaseMetaData jdbcMetadata = connection.getMetaData();
            MssqlConnectionMetadata metadata = IntegrationTestSupport.connection.getMetadata();

            assertThat(metadata.getDatabaseProductName()).contains(jdbcMetadata.getDatabaseProductName());
            assertThat(metadata.getDatabaseProductName()).doesNotContain("Copyright");
            assertThat(metadata.getDatabaseVersion()).isEqualTo(jdbcMetadata.getDatabaseProductVersion());
        }
    }

    @Test
    void shouldInsertAndSelectUsingMap() {

        createTable(connection);

        insertRecord(connection, 1);

        connection.createStatement("SELECT * FROM r2dbc_example ORDER BY first_name")
            .execute()
            .flatMap(it -> it.map((row, rowMetadata) -> {

                Map<String, Object> values = new LinkedHashMap<>();

                for (ColumnMetadata column : rowMetadata.getColumnMetadatas()) {
                    values.put(column.getName(), row.get(column.getName()));
                }

                return values;
            }))
            .as(StepVerifier::create)
            .consumeNextWith(actual -> {

                assertThat(actual)
                    .containsEntry("id", 1)
                    .containsEntry("first_name", "Walter")
                    .containsEntry("last_name", "White");
            })
            .verifyComplete();
    }

    @Test
    void shouldInsertAndSelectUsingRowCount() {

        createTable(connection);

        insertRecord(connection, 1);
        insertRecord(connection, 2);
        insertRecord(connection, 3);

        connection.createStatement("SELECT * FROM r2dbc_example")
            .execute()
            .flatMap(MssqlResult::getRowsUpdated)
            .as(StepVerifier::create)
            .expectNext(3)
            .verifyComplete();
    }

    @Test
    void shouldInsertAndSelectUsingPagingAndDirectMode() {

        createTable(connection);

        insertRecord(connection, 1);
        insertRecord(connection, 2);
        insertRecord(connection, 3);

        Flux.from(connection.createStatement("SELECT * FROM r2dbc_example ORDER BY id OFFSET @Offset ROWS" +
            "  FETCH NEXT @Rows ROWS ONLY")
            .bind("Offset", 0)
            .bind("Rows", 2)
            .execute())
            .flatMap(it -> it.map((row, rowMetadata) -> row.get("id", Integer.class)))
            .as(StepVerifier::create)
            .expectNext(1)
            .expectNext(2)
            .verifyComplete();

        Flux.from(connection.createStatement("SELECT * FROM r2dbc_example ORDER BY id OFFSET @Offset ROWS" +
            " FETCH NEXT @Rows ROWS ONLY")
            .bind("Offset", 2)
            .bind("Rows", 2)
            .execute())
            .flatMap(it -> it.map((row, rowMetadata) -> row.get("id", Integer.class)))
            .as(StepVerifier::create)
            .expectNext(3)
            .verifyComplete();
    }

    @Test
    void shouldInsertAndSelectUsingPagingAndCursors() {

        createTable(connection);

        insertRecord(connection, 1);
        insertRecord(connection, 2);
        insertRecord(connection, 3);

        Flux.from(connection.createStatement("SELECT * FROM r2dbc_example ORDER BY id OFFSET @Offset ROWS" +
            "  FETCH NEXT @Rows ROWS ONLY /* cursored */")
            .bind("Offset", 0)
            .bind("Rows", 2)
            .execute())
            .flatMap(it -> it.map((row, rowMetadata) -> row.get("id", Integer.class)))
            .as(StepVerifier::create)
            .expectNext(1)
            .expectNext(2)
            .verifyComplete();
    }

    @Test
    void shouldInsertAndSelectCompoundStatement() {

        createTable(connection);

        connection.createStatement("SELECT * FROM r2dbc_example;SELECT * FROM r2dbc_example")
            .execute()
            .flatMap(it -> it.map((row, rowMetadata) -> {
                return new Object();   // just a marker
            }).collectList())
            .as(StepVerifier::create)
            .consumeNextWith(actual -> assertThat(actual).isEmpty())
            .consumeNextWith(actual -> assertThat(actual).isEmpty())
            .verifyComplete();

        insertRecord(connection, 1);

        connection.createStatement("SELECT * FROM r2dbc_example;SELECT * FROM r2dbc_example")
            .execute()
            .flatMap(it -> it.map((row, rowMetadata) -> {

                Map<String, Object> values = new LinkedHashMap<>();

                for (ColumnMetadata column : rowMetadata.getColumnMetadatas()) {
                    values.put(column.getName(), row.get(column.getName()));
                }

                return values;
            }).collectList())
            .as(StepVerifier::create)
            .consumeNextWith(actual -> assertThat(actual).hasSize(1))
            .consumeNextWith(actual -> assertThat(actual).hasSize(1))
            .verifyComplete();
    }

    @Test
    void shouldConsumeSequence() {

        createSequence(connection);

        connection.createStatement("SELECT CAST(NEXT VALUE FOR integration_test AS BIGINT)")
            .execute()
            .flatMap(it -> it.map((row, rowMetadata) -> row.get(0)))
            .as(StepVerifier::create)
            .expectNext(1L)
            .verifyComplete();


        connection.createStatement("SELECT CAST(NEXT VALUE FOR integration_test AS BIGINT)")
            .execute()
            .flatMap(it -> it.map((row, rowMetadata) -> row.get(0)))
            .as(StepVerifier::create)
            .expectNext(2L)
            .verifyComplete();
    }

    @Test
    void shouldReusePreparedStatements() {

        createTable(connection);

        insertRecord(connection, 1);
        insertRecord(connection, 2);
    }

    @Test
    void shouldApplyLockWaitwaot() {

        ConnectionFactoryOptions options = builder().option(LOCK_WAIT_TIMEOUT, Duration.ofMillis(100)).build();

        MssqlConnectionFactory factory = (MssqlConnectionFactory) ConnectionFactories.get(options);
        MssqlConnection connection1 = factory.create().block();
        MssqlConnection connection2 = factory.create().block();

        connection1.createStatement("SELECT @@LOCK_TIMEOUT AS [Lock Timeout]").execute()
            .flatMap(it -> it.map(row -> row.get("Lock Timeout")))
            .as(StepVerifier::create)
            .expectNext(100)
            .verifyComplete();

        createTable(connection);

        connection1.createStatement("INSERT INTO r2dbc_example VALUES (1, 'fn', 'ln')")
            .execute()
            .flatMap(Result::getRowsUpdated)
            .then()
            .as(StepVerifier::create)
            .verifyComplete();

        connection1.beginTransaction(IsolationLevel.READ_COMMITTED).as(StepVerifier::create).verifyComplete();
        connection2.beginTransaction(IsolationLevel.READ_COMMITTED).as(StepVerifier::create).verifyComplete();

        connection1.createStatement("SELECT * FROM r2dbc_example WITH (UPDLOCK) WHERE id = 1")
            .execute()
            .flatMap(Result::getRowsUpdated)
            .then()
            .as(StepVerifier::create)
            .verifyComplete();

        connection2.createStatement("UPDATE r2dbc_example SET first_name = 'updated' WHERE id = 1")
            .execute()
            .flatMap(Result::getRowsUpdated)
            .then()
            .as(StepVerifier::create)
            .verifyError(R2dbcTimeoutException.class);

        connection1.close().block();
        connection2.close().block();
    }

    @Test
    void queryAfterCancelShouldCompleteSuccessfully() {

        connection.cancel().as(StepVerifier::create).verifyComplete();

        connection.createStatement("SELECT 1").execute().flatMap(it -> it.map(row -> row.get(0))).as(StepVerifier::create).expectNext(1).verifyComplete();
    }

    @Test
    void cancelShouldTerminateOngoingDirectQuery() throws Exception {

        CompletableFuture<Void> future = connection.createStatement("WAITFOR DELAY '10:00'").fetchSize(0).execute().flatMap(Result::getRowsUpdated).then().toFuture();
        connection.cancel().as(StepVerifier::create).verifyComplete();

        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(R2dbcTransientException.class).hasMessageContaining("cancelled");
        }

        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void cancelShouldTerminateOngoingCursoredQuery() throws Exception {

        CompletableFuture<Void> future = connection.createStatement("WAITFOR DELAY '10:00'").fetchSize(10).execute().flatMap(Result::getRowsUpdated).then().toFuture();
        connection.cancel().as(StepVerifier::create).verifyComplete();

        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(R2dbcTransientException.class).hasMessageContaining("cancelled");
        }

        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void shouldResumeOperationsAfterCancellationOfPendingRequest() throws Exception {

        CompletableFuture<Void> future = connection.createStatement("WAITFOR DELAY '10:00'").execute().flatMap(Result::getRowsUpdated).then().toFuture();
        connection.cancel().as(StepVerifier::create).verifyComplete();

        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(R2dbcTransientException.class).hasMessageContaining("cancelled");
        }

        connection.createStatement("SELECT 1").execute().flatMap(it -> it.map(row -> row.get(0))).as(StepVerifier::create).expectNext(1).verifyComplete();
    }

    @Test
    void shouldRejectMultipleParametrizedExecutions() {

        createTable(connection);

        Flux<MssqlResult> prepared = Flux.from(connection.createStatement("SELECT * FROM r2dbc_example ORDER BY id OFFSET @Offset ROWS")
            .bind("Offset", 0)
            .execute());

        prepared.flatMap(MssqlResult::getRowsUpdated)
            .as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();

        prepared.flatMap(MssqlResult::getRowsUpdated)
            .as(StepVerifier::create)
            .verifyError(IllegalStateException.class);
    }

    private void createTable(MssqlConnection connection) {

        connection.createStatement("DROP TABLE r2dbc_example").execute()
            .flatMap(MssqlResult::getRowsUpdated)
            .onErrorResume(e -> Mono.empty())
            .thenMany(connection.createStatement("CREATE TABLE r2dbc_example (" +
                "id int PRIMARY KEY, " +
                "first_name varchar(255), " +
                "last_name varchar(255))")
                .execute().flatMap(MssqlResult::getRowsUpdated).then())
            .as(StepVerifier::create)
            .verifyComplete();
    }

    private void createSequence(MssqlConnection connection) {

        connection.createStatement("DROP SEQUENCE integration_test").execute()
            .flatMap(MssqlResult::getRowsUpdated)
            .onErrorResume(e -> Mono.empty())
            .thenMany(connection.createStatement("CREATE SEQUENCE integration_test START WITH 1 INCREMENT BY 1")
                .execute().flatMap(MssqlResult::getRowsUpdated).then())
            .as(StepVerifier::create)
            .verifyComplete();
    }

    private String writeKeyStoreToTempFile(Path tempDir, KeyStore keyStore, String password) {
        final File file = new File(tempDir.toFile(), UUID.randomUUID().toString() + ".jks");
        try (OutputStream outputStream = new FileOutputStream(file)) {
            keyStore.store(outputStream, password.toCharArray());
            return file.getAbsolutePath();
        } catch (final IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
            throw new RuntimeException("Failed to write key store to file", e);
        }
    }

    private void insertRecord(MssqlConnection connection, int id) {

        Flux.from(connection.createStatement("INSERT INTO r2dbc_example VALUES(@id, @firstname, @lastname)")
            .bind("id", id)
            .bind("firstname", "Walter")
            .bind("lastname", "White")
            .execute())
            .flatMap(Result::getRowsUpdated)
            .as(StepVerifier::create)
            .expectNext(1)
            .verifyComplete();
    }
}
