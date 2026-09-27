package com.clinicit.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database is unreachable: readiness must say DOWN (stop sending traffic here) while
 * liveness stays UP (do not restart the process; it recovers when the database is back).
 *
 * <p>The application is started without Flyway and without Hibernate reading database
 * metadata, which is what lets it boot at all with no database; in production Flyway needs
 * the database at startup, and this models the database going away afterwards. Needs no
 * PostgreSQL, so it also runs where PostgreSQL is unavailable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "clinicit.scheduling.enabled=false",
        "management.server.port=0"
})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = false)
class ProbesWithoutDatabaseTest {

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;

    private HttpResponse<String> fetch(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void livenessStaysUpWhileReadinessReportsTheDatabaseDown() throws Exception {
        HttpResponse<String> liveness = fetch(managementPort, "/actuator/health/liveness");
        HttpResponse<String> readiness = fetch(managementPort, "/actuator/health/readiness");

        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"").contains("\"db\"");
        // The public liveness ping keeps answering too.
        assertThat(fetch(port, "/api/v1/health").statusCode()).isEqualTo(200);
        // And metrics still render: the backlog gauge reports no value rather than failing.
        assertThat(fetch(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
    }
}
