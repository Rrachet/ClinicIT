package com.clinicit.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL for integration tests. The queue engine relies on Postgres
 * semantics (row locks, SKIP LOCKED, ON CONFLICT, partial unique indexes)
 * that H2 does not reproduce, so these tests never run against H2.
 *
 * <p>Uses the database in {@code CLINICIT_TEST_DB_URL} (plus optional
 * {@code CLINICIT_TEST_DB_USERNAME} / {@code CLINICIT_TEST_DB_PASSWORD}) when set,
 * otherwise starts a Testcontainers PostgreSQL shared by all test classes.
 */
public final class PostgresTestDatabase {

    private static final String URL_ENV = "CLINICIT_TEST_DB_URL";

    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {}

    /** When false, Postgres-backed tests are skipped rather than failed. */
    public static boolean isAvailable() {
        return System.getenv(URL_ENV) != null || DockerClientFactory.instance().isDockerAvailable();
    }

    public static synchronized String url() {
        String external = System.getenv(URL_ENV);
        return external != null ? external : container().getJdbcUrl();
    }

    public static synchronized String username() {
        return System.getenv(URL_ENV) != null
                ? System.getenv().getOrDefault("CLINICIT_TEST_DB_USERNAME", "postgres")
                : container().getUsername();
    }

    public static synchronized String password() {
        return System.getenv(URL_ENV) != null
                ? System.getenv().getOrDefault("CLINICIT_TEST_DB_PASSWORD", "")
                : container().getPassword();
    }

    private static PostgreSQLContainer<?> container() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
        return container;
    }
}
