package com.clinicit.migration;

import com.clinicit.support.PostgresIntegrationTest;
import com.clinicit.support.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rest of the suite migrates an empty database. This upgrades a database that
 * already holds Phase 1 data, which is what a real deployment will do.
 */
class FlywayMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "migration_upgrade_test";

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
    }

    private Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .target(target)
                .load();
    }

    @Test
    void upgradesPopulatedPhase1DatabaseAndBackfillsQueueData() throws Exception {
        // Own unpooled connections: Flyway and setSchema change session state (search_path)
        // that must not leak into the application's pool used by other tests.
        DataSource dataSource = new DriverManagerDataSource(
                PostgresTestDatabase.url(), PostgresTestDatabase.username(), PostgresTestDatabase.password());
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        flyway(dataSource, "2").migrate();

        UUID clinic = UUID.randomUUID(), doctor = UUID.randomUUID(), patient = UUID.randomUUID();
        UUID appointment1 = UUID.randomUUID(), appointment2 = UUID.randomUUID();

        try (var connection = dataSource.getConnection()) {
            connection.setSchema(SCHEMA);
            JdbcTemplate v2 = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            v2.update("insert into clinics (id, name, timezone) values (?, 'Old Clinic', 'Asia/Kolkata')", clinic);
            v2.update("insert into doctor_profiles (id, clinic_id, display_name, created_at) values (?, ?, 'Dr. Old', now())",
                    doctor, clinic);
            v2.update("""
                    insert into patients (id, clinic_id, full_name, phone, created_at, updated_at)
                    values (?, ?, 'Old Patient', '1', now(), now())""", patient, clinic);
            for (UUID appointment : new UUID[]{appointment1, appointment2}) {
                v2.update("""
                        insert into appointments (id, clinic_id, patient_id, doctor_id, scheduled_at, status, created_at, updated_at)
                        values (?, ?, ?, ?, '2026-03-10 16:00', 'WAITING', now(), now())""",
                        appointment, clinic, patient, doctor);
            }
            v2.update("""
                    insert into queue_entries (id, appointment_id, clinic_id, queue_date, token_number, status, created_at)
                    values (?, ?, ?, '2026-03-10', 7, 'WAITING', now()),
                           (?, ?, ?, '2026-03-10', 9, 'WAITING', now())""",
                    UUID.randomUUID(), appointment1, clinic, UUID.randomUUID(), appointment2, clinic);
        }

        flyway(dataSource, "latest").migrate();

        try (var connection = dataSource.getConnection()) {
            connection.setSchema(SCHEMA);
            JdbcTemplate latest = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

            assertThat(latest.queryForList("select distinct doctor_id from queue_entries", UUID.class))
                    .containsExactly(doctor);
            Map<String, Object> counter = latest.queryForMap("select clinic_id, last_token from queue_token_counters");
            assertThat(counter).containsEntry("clinic_id", clinic).containsEntry("last_token", 9);
            assertThat(latest.queryForObject("select count(*) from clinics where created_at is not null", Integer.class))
                    .isEqualTo(1);
        }
    }
}
